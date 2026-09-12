//! Versioned SPSC shared-memory ring protocol — the Rust twin of the Java
//! `SharedRing` (identical header/slot layout, little-endian, see
//! `code/fixtures/shared-memory-ipc-fixtures.json`). Exactly one process
//! ever writes `writer_seq`, exactly one ever writes `reader_seq`; the
//! release/acquire discipline is what makes cross-process visibility
//! correct here, the same mechanism the `spsc-ring-buffer` lab
//! establishes for cross-thread visibility.

use memmap2::{MmapMut, MmapOptions};
use std::fs::OpenOptions;
use std::io::{Error, ErrorKind, Result as IoResult};
use std::path::Path;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};

pub const PROTOCOL_VERSION: i32 = 1;
pub const HEADER_SIZE: usize = 192;
pub const SLOT_SIZE: usize = 1024;
pub const SLOT_HEADER_SIZE: usize = 4;
pub const PAYLOAD_CAPACITY: usize = SLOT_SIZE - SLOT_HEADER_SIZE;

const OFF_CAPACITY: usize = 0;
const OFF_PROTOCOL_VERSION: usize = 4;
const OFF_WRITER_SEQ: usize = 64;
const OFF_READER_SEQ: usize = 128;

pub fn segment_size(capacity: u32) -> u64 {
    HEADER_SIZE as u64 + capacity as u64 * SLOT_SIZE as u64
}

pub fn payload(message_index: u64, size: usize) -> Vec<u8> {
    (0..size)
        .map(|j| ((message_index.wrapping_add(j as u64)) & 0xFF) as u8)
        .collect()
}

pub fn checksum(message_index: u64, payload: &[u8]) -> u64 {
    let mut h = message_index.wrapping_mul(31);
    for &b in payload {
        h = h.wrapping_mul(31).wrapping_add(b as u64);
    }
    h
}

/// A versioned SPSC ring over a memory-mapped shared backing file.
///
/// # Safety / isolation note
/// The cursor fields (`writer_seq`/`reader_seq`) are accessed through
/// `AtomicU64` references constructed from raw pointers into the mapped
/// region (`&*(ptr as *const AtomicU64)`). This is sound because: the
/// offsets (64, 128) are 8-byte aligned (the mapping's base address is
/// page-aligned, far coarser than 8 bytes); every access to those two
/// offsets in this entire crate goes through the same atomic reference
/// construction, never a plain read/write; and the referenced memory
/// outlives every use (owned by `self.mmap` for the ring's lifetime).
/// This is the same construction pattern used by production shared-memory
/// crates for exactly this reason — see sources.md. It is the ONLY
/// unsafe pattern in this module; every slot read/write below it is
/// ordinary safe slice indexing.
pub struct SharedRing {
    mmap: MmapMut,
    capacity: u32,
    reserve_index: i64, // -1 = uninitialized
    cached_tail: u64,
    read_index: i64, // -1 = uninitialized
    cached_head: u64,
}

unsafe fn atomic_u64_at(mmap: &MmapMut, offset: usize) -> &AtomicU64 {
    let ptr = mmap.as_ptr().add(offset) as *const AtomicU64;
    &*ptr
}

unsafe fn atomic_i32_at(mmap: &MmapMut, offset: usize) -> &AtomicI32 {
    let ptr = mmap.as_ptr().add(offset) as *const AtomicI32;
    &*ptr
}

impl SharedRing {
    pub fn create_new(path: &Path, capacity: u32) -> IoResult<SharedRing> {
        let size = segment_size(capacity);
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)?;
        file.set_len(size)?;
        let mmap = unsafe { MmapOptions::new().map_mut(&file) }?;
        // SAFETY: see struct-level isolation note; offsets are 8-byte aligned.
        unsafe {
            atomic_i32_at(&mmap, OFF_CAPACITY).store(capacity as i32, Ordering::Relaxed);
            atomic_i32_at(&mmap, OFF_PROTOCOL_VERSION).store(PROTOCOL_VERSION, Ordering::Relaxed);
            atomic_u64_at(&mmap, OFF_WRITER_SEQ).store(0, Ordering::Relaxed);
            atomic_u64_at(&mmap, OFF_READER_SEQ).store(0, Ordering::Relaxed);
        }
        Ok(SharedRing {
            mmap,
            capacity,
            reserve_index: -1,
            cached_tail: 0,
            read_index: -1,
            cached_head: 0,
        })
    }

    pub fn open_existing(path: &Path) -> IoResult<SharedRing> {
        let file = OpenOptions::new().read(true).write(true).open(path)?;
        let file_size = file.metadata()?.len();
        if (file_size as usize) < HEADER_SIZE {
            return Err(Error::new(
                ErrorKind::InvalidData,
                "segment file too small for header",
            ));
        }
        let mmap = unsafe { MmapOptions::new().map_mut(&file) }?;
        // SAFETY: see struct-level isolation note.
        let protocol_version =
            unsafe { atomic_i32_at(&mmap, OFF_PROTOCOL_VERSION).load(Ordering::Relaxed) };
        if protocol_version != PROTOCOL_VERSION {
            return Err(Error::new(
                ErrorKind::InvalidData,
                format!("protocol version mismatch: segment={protocol_version} reader={PROTOCOL_VERSION}"),
            ));
        }
        let capacity = unsafe { atomic_i32_at(&mmap, OFF_CAPACITY).load(Ordering::Relaxed) } as u32;
        Ok(SharedRing {
            mmap,
            capacity,
            reserve_index: -1,
            cached_tail: 0,
            read_index: -1,
            cached_head: 0,
        })
    }

    pub fn capacity(&self) -> u32 {
        self.capacity
    }

    fn writer_seq_ref(&self) -> &AtomicU64 {
        unsafe { atomic_u64_at(&self.mmap, OFF_WRITER_SEQ) }
    }

    fn reader_seq_ref(&self) -> &AtomicU64 {
        unsafe { atomic_u64_at(&self.mmap, OFF_READER_SEQ) }
    }

    pub fn writer_seq(&self) -> u64 {
        self.writer_seq_ref().load(Ordering::Relaxed)
    }

    pub fn reader_seq(&self) -> u64 {
        self.reader_seq_ref().load(Ordering::Relaxed)
    }

    pub fn init_writer(&mut self) {
        self.reserve_index = 0;
        self.cached_tail = 0;
    }

    pub fn resume_writer(&mut self) {
        self.reserve_index = self.writer_seq() as i64;
        self.cached_tail = self.reader_seq_ref().load(Ordering::Acquire);
    }

    pub fn init_reader(&mut self) {
        self.read_index = self.reader_seq() as i64;
        self.cached_head = 0;
    }

    fn slot_offset(&self, index: u64) -> usize {
        HEADER_SIZE + (index % self.capacity as u64) as usize * SLOT_SIZE
    }

    pub fn try_publish(&mut self, payload: &[u8]) -> bool {
        assert!(
            self.reserve_index >= 0,
            "call init_writer()/resume_writer() first"
        );
        assert!(payload.len() <= PAYLOAD_CAPACITY, "payload too large");
        let reserve_index = self.reserve_index as u64;
        if reserve_index - self.cached_tail == self.capacity as u64 {
            self.cached_tail = self.reader_seq_ref().load(Ordering::Acquire);
            if reserve_index - self.cached_tail == self.capacity as u64 {
                return false;
            }
        }
        self.write_slot(reserve_index, payload);
        self.reserve_index += 1;
        self.writer_seq_ref()
            .store(self.reserve_index as u64, Ordering::Release);
        true
    }

    /// Publishes as many of `payloads` as fit, with exactly one header
    /// (writer_seq) update at the end. Returns the number published.
    pub fn try_publish_batch(&mut self, payloads: &[&[u8]]) -> usize {
        let mut published = 0;
        for payload in payloads {
            let reserve_index = self.reserve_index as u64;
            if reserve_index - self.cached_tail == self.capacity as u64 {
                self.cached_tail = self.reader_seq_ref().load(Ordering::Acquire);
                if reserve_index - self.cached_tail == self.capacity as u64 {
                    break;
                }
            }
            self.write_slot(reserve_index, payload);
            self.reserve_index += 1;
            published += 1;
        }
        if published > 0 {
            self.writer_seq_ref()
                .store(self.reserve_index as u64, Ordering::Release);
        }
        published
    }

    fn write_slot(&mut self, index: u64, payload: &[u8]) {
        let off = self.slot_offset(index);
        let len = payload.len() as u16;
        self.mmap[off..off + 2].copy_from_slice(&len.to_le_bytes());
        self.mmap[off + 2] = PROTOCOL_VERSION as u8;
        self.mmap[off + 3] = 0;
        self.mmap[off + SLOT_HEADER_SIZE..off + SLOT_HEADER_SIZE + payload.len()]
            .copy_from_slice(payload);
    }

    /// Copies one message's payload into `out`; returns its length, or `None` if genuinely empty.
    pub fn try_consume(&mut self, out: &mut [u8]) -> Option<usize> {
        assert!(self.read_index >= 0, "call init_reader() first");
        let read_index = self.read_index as u64;
        if read_index == self.cached_head {
            self.cached_head = self.writer_seq_ref().load(Ordering::Acquire);
            if read_index == self.cached_head {
                return None;
            }
        }
        let off = self.slot_offset(read_index);
        let len = u16::from_le_bytes(self.mmap[off..off + 2].try_into().unwrap()) as usize;
        out[..len]
            .copy_from_slice(&self.mmap[off + SLOT_HEADER_SIZE..off + SLOT_HEADER_SIZE + len]);
        self.read_index += 1;
        self.reader_seq_ref()
            .store(self.read_index as u64, Ordering::Release);
        Some(len)
    }

    /// Zero-copy variant: returns a borrowed slice view over the slot instead of copying out.
    pub fn try_consume_view(&mut self) -> Option<&[u8]> {
        assert!(self.read_index >= 0, "call init_reader() first");
        let read_index = self.read_index as u64;
        if read_index == self.cached_head {
            self.cached_head = self.writer_seq_ref().load(Ordering::Acquire);
            if read_index == self.cached_head {
                return None;
            }
        }
        let off = self.slot_offset(read_index);
        let len = u16::from_le_bytes(self.mmap[off..off + 2].try_into().unwrap()) as usize;
        self.read_index += 1;
        self.reader_seq_ref()
            .store(self.read_index as u64, Ordering::Release);
        Some(&self.mmap[off + SLOT_HEADER_SIZE..off + SLOT_HEADER_SIZE + len])
    }
}

/// Variant: pipe/socket baseline — a loopback TCP connection carries the
/// same fixed messages the shared-memory variants carry. Every message
/// crosses the real kernel network stack, exactly as it would between two
/// processes; the thread-per-endpoint structure here is a benchmarking
/// convenience for the socket variant specifically (sockets have no
/// same-process-threads trap the way heap sharing does — see java.md).
pub fn run_socket_baseline(message_count: u64, payload_size: usize) -> IoResult<u64> {
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};

    let listener = TcpListener::bind("127.0.0.1:0")?;
    let addr = listener.local_addr()?;

    let receiver = std::thread::spawn(move || -> IoResult<u64> {
        let (mut stream, _) = listener.accept()?;
        let mut buf = vec![0u8; payload_size];
        let mut total = 0u64;
        for i in 0..message_count {
            stream.read_exact(&mut buf)?;
            total = total.wrapping_add(checksum(i, &buf));
        }
        Ok(total)
    });

    let mut stream = TcpStream::connect(addr)?;
    for i in 0..message_count {
        stream.write_all(&payload(i, payload_size))?;
    }
    stream.flush()?;
    drop(stream);

    receiver.join().expect("receiver thread panicked")
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn sequential_publish_and_consume_round_trips() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("ring.bin");
        let mut writer = SharedRing::create_new(&path, 8).unwrap();
        writer.init_writer();
        let mut reader = SharedRing::open_existing(&path).unwrap();
        reader.init_reader();

        for i in 0..100u64 {
            let p = payload(i, 32);
            assert!(writer.try_publish(&p));
            let mut out = [0u8; PAYLOAD_CAPACITY];
            let mut len = None;
            for _ in 0..1_000_000 {
                len = reader.try_consume(&mut out);
                if len.is_some() {
                    break;
                }
            }
            let len = len.unwrap();
            assert_eq!(len, 32);
            assert_eq!(&out[..32], &p[..]);
        }
    }

    #[test]
    fn ring_rejects_publish_when_genuinely_full() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("ring.bin");
        let mut writer = SharedRing::create_new(&path, 4).unwrap();
        writer.init_writer();
        for i in 0..4u64 {
            assert!(writer.try_publish(&payload(i, 16)));
        }
        assert!(!writer.try_publish(&payload(4, 16)));
    }

    #[test]
    fn batch_publish_delivers_every_message_with_one_header_update() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("ring.bin");
        let mut writer = SharedRing::create_new(&path, 16).unwrap();
        writer.init_writer();
        let mut reader = SharedRing::open_existing(&path).unwrap();
        reader.init_reader();

        let payloads: Vec<Vec<u8>> = (0..10u64).map(|i| payload(i, 16)).collect();
        let refs: Vec<&[u8]> = payloads.iter().map(|p| p.as_slice()).collect();
        let published = writer.try_publish_batch(&refs);
        assert_eq!(published, 10);
        assert_eq!(writer.writer_seq(), 10);

        let mut out = [0u8; PAYLOAD_CAPACITY];
        for _ in 0..10 {
            let mut len = None;
            for _ in 0..1_000_000 {
                len = reader.try_consume(&mut out);
                if len.is_some() {
                    break;
                }
            }
            assert_eq!(len.unwrap(), 16);
        }
    }

    #[test]
    fn slot_view_matches_copying_consume() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("ring.bin");
        let mut writer = SharedRing::create_new(&path, 8).unwrap();
        writer.init_writer();
        let mut reader = SharedRing::open_existing(&path).unwrap();
        reader.init_reader();

        let p = payload(7, 32);
        writer.try_publish(&p);

        let mut view = None;
        for _ in 0..1_000_000 {
            view = reader.try_consume_view();
            if view.is_some() {
                break;
            }
        }
        assert_eq!(view.unwrap(), &p[..]);
    }

    #[test]
    fn opening_a_segment_with_too_small_a_file_fails() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("bad.bin");
        std::fs::write(&path, [0u8; 10]).unwrap();
        assert!(SharedRing::open_existing(&path).is_err());
    }
}
