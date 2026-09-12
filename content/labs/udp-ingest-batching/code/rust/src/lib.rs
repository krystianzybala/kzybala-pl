//! UDP ingest variants over loopback (127.0.0.1) — this lab tests
//! exclusively over loopback and labels that fact everywhere a result
//! appears (see the "testing only loopback without labeling" trap).
//! Wire format identical to the Java side: 8-byte little-endian sequence
//! number + deterministic payload pattern, fixed to one of three total
//! datagram sizes (64B/256B/1400B).

use std::net::UdpSocket;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub const SEQUENCE_SIZE: usize = 8;

pub fn payload_size(datagram_size: usize) -> usize {
    datagram_size - SEQUENCE_SIZE
}

pub fn build_datagram(sequence: u64, datagram_size: usize) -> Vec<u8> {
    let mut out = vec![0u8; datagram_size];
    out[0..8].copy_from_slice(&sequence.to_le_bytes());
    let psize = payload_size(datagram_size);
    for i in 0..psize {
        out[SEQUENCE_SIZE + i] = ((sequence.wrapping_add(i as u64)) & 0xFF) as u8;
    }
    out
}

pub fn read_sequence(buf: &[u8]) -> u64 {
    u64::from_le_bytes(buf[0..8].try_into().unwrap())
}

pub fn payload_matches(buf: &[u8], sequence: u64) -> bool {
    let psize = payload_size(buf.len());
    for i in 0..psize {
        let expected = ((sequence.wrapping_add(i as u64)) & 0xFF) as u8;
        if buf[SEQUENCE_SIZE + i] != expected {
            return false;
        }
    }
    true
}

/// Sends `message_count` deterministic datagrams to a loopback UDP port,
/// back-to-back with no pacing (the "overload"/burst-style profile).
pub fn send_burst(port: u16, message_count: u64, datagram_size: usize) -> std::io::Result<()> {
    let socket = UdpSocket::bind("127.0.0.1:0")?;
    socket.connect(("127.0.0.1", port))?;
    for seq in 0..message_count {
        let datagram = build_datagram(seq, datagram_size);
        socket.send(&datagram)?;
    }
    Ok(())
}

/// Sends messages with an explicit inter-send delay — the "steady rate" profile.
pub fn send_steady(
    port: u16,
    message_count: u64,
    datagram_size: usize,
    inter_send: Duration,
) -> std::io::Result<()> {
    let socket = UdpSocket::bind("127.0.0.1:0")?;
    socket.connect(("127.0.0.1", port))?;
    for seq in 0..message_count {
        let datagram = build_datagram(seq, datagram_size);
        socket.send(&datagram)?;
        thread::sleep(inter_send);
    }
    Ok(())
}

/// Every datagram sent is accounted for as exactly one of delivered,
/// application-dropped (bounded path was full — explicit, never silent),
/// or corrupted (should never happen over loopback). See
/// `code/fixtures/udp-ingest-batching-fixtures.json`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IngestResult {
    pub delivered: u64,
    pub application_dropped: u64,
    pub corrupted: u64,
}

impl IngestResult {
    pub fn accounted_for(&self) -> u64 {
        self.delivered + self.application_dropped + self.corrupted
    }
}

/// Variants: one-packet-per-receive and reused-buffer. Both measure raw
/// receive-loop cost with no downstream handoff — every datagram is
/// validated in-line and discarded immediately.
pub struct RawReceiveKernel {
    socket: UdpSocket,
}

impl RawReceiveKernel {
    pub fn bind(port: u16) -> std::io::Result<Self> {
        Ok(RawReceiveKernel {
            socket: UdpSocket::bind(("127.0.0.1", port))?,
        })
    }

    /// Allocates a fresh buffer for every single receive call.
    pub fn receive_allocating_per_packet(
        &self,
        message_count: u64,
        datagram_size: usize,
    ) -> std::io::Result<u64> {
        let mut validated = 0u64;
        for _ in 0..message_count {
            let mut buf = vec![0u8; datagram_size];
            let (len, _) = self.socket.recv_from(&mut buf)?;
            let seq = read_sequence(&buf[..len]);
            if payload_matches(&buf[..len], seq) {
                validated += 1;
            }
        }
        Ok(validated)
    }

    /// Reuses one buffer across every receive call — no per-packet allocation.
    pub fn receive_with_reused_buffer(
        &self,
        message_count: u64,
        datagram_size: usize,
    ) -> std::io::Result<u64> {
        let mut buf = vec![0u8; datagram_size];
        let mut validated = 0u64;
        for _ in 0..message_count {
            let (len, _) = self.socket.recv_from(&mut buf)?;
            let seq = read_sequence(&buf[..len]);
            if payload_matches(&buf[..len], seq) {
                validated += 1;
            }
        }
        Ok(validated)
    }
}

/// Variant: copying handoff. The receive loop reuses one buffer but
/// copies every datagram into a fresh `Vec<u8>` before sending it to a
/// bounded channel — safe to reuse the receive buffer immediately after.
/// When the bounded channel is full, the datagram is still drained off
/// the socket but explicitly discarded and counted (never a silent,
/// unbounded queue).
pub struct CopyingHandoffPipeline {
    socket: UdpSocket,
    capacity: usize,
}

impl CopyingHandoffPipeline {
    pub fn bind(port: u16, capacity: usize) -> std::io::Result<Self> {
        Ok(CopyingHandoffPipeline {
            socket: UdpSocket::bind(("127.0.0.1", port))?,
            capacity,
        })
    }

    pub fn run(&self, message_count: u64, datagram_size: usize) -> std::io::Result<IngestResult> {
        let (tx, rx): (SyncSender<Vec<u8>>, Receiver<Vec<u8>>) = sync_channel(self.capacity);
        let delivered = Arc::new(AtomicUsize::new(0));
        let corrupted = Arc::new(AtomicUsize::new(0));
        let delivered_c = delivered.clone();
        let corrupted_c = corrupted.clone();

        let consumer = thread::spawn(move || {
            while let Ok(datagram) = rx.recv() {
                let seq = read_sequence(&datagram);
                if payload_matches(&datagram, seq) {
                    delivered_c.fetch_add(1, Ordering::Relaxed);
                } else {
                    corrupted_c.fetch_add(1, Ordering::Relaxed);
                }
            }
        });

        let mut buf = vec![0u8; datagram_size];
        let mut application_dropped = 0u64;
        for _ in 0..message_count {
            let (len, _) = self.socket.recv_from(&mut buf)?;
            let copy = buf[..len].to_vec();
            if tx.try_send(copy).is_err() {
                application_dropped += 1;
            }
        }
        drop(tx);
        consumer.join().expect("consumer thread panicked");

        Ok(IngestResult {
            delivered: delivered.load(Ordering::Relaxed) as u64,
            application_dropped,
            corrupted: corrupted.load(Ordering::Relaxed) as u64,
        })
    }
}

/// A slab of preallocated buffers shared between the receiver and
/// consumer threads.
///
/// # Safety / isolation note
/// `Slab` is `unsafe impl Sync` so a shared `Arc<Slab>` can be handed to
/// both threads even though `UnsafeCell` is `!Sync` by default. This is
/// sound only because of the discipline enforced entirely by
/// [`ZeroCopyHandoffPipeline::run`]: the receiver thread never writes
/// into slot `i % slab_size` until it has received, via the `freed`
/// channel, confirmation that the consumer thread is done reading that
/// exact slot — so the two threads never access the same slot
/// concurrently, even though the type system cannot see that protocol.
/// This is the same category of reasoning as `shared-memory-ipc`'s
/// `AtomicU64`-from-raw-pointer pattern: a single well-documented unsafe
/// boundary standing in for a protocol invariant the compiler can't
/// express, isolated to this one struct.
struct Slab {
    buffers: Vec<std::cell::UnsafeCell<Vec<u8>>>,
}

unsafe impl Sync for Slab {}

impl Slab {
    fn new(size: usize, datagram_size: usize) -> Self {
        Slab {
            buffers: (0..size)
                .map(|_| std::cell::UnsafeCell::new(vec![0u8; datagram_size]))
                .collect(),
        }
    }

    /// # Safety
    /// Caller must guarantee no other thread is concurrently accessing `index`.
    #[allow(clippy::mut_from_ref)] // interior mutability via UnsafeCell, protocol-guaranteed exclusivity — see struct docs
    unsafe fn get_mut(&self, index: usize) -> &mut Vec<u8> {
        &mut *self.buffers[index].get()
    }

    /// # Safety
    /// Caller must guarantee no other thread is concurrently writing `index`.
    unsafe fn get(&self, index: usize) -> &Vec<u8> {
        &*self.buffers[index].get()
    }
}

/// Variant: zero-copy view handoff with a bounded lifetime. The receiver
/// writes directly into a slab slot (no copy); the consumer reads that
/// same slot in place. The slab size equals the bounded channel capacity,
/// which is what makes the lifetime bound safe: the receiver only reuses
/// slot `i % slab_size` once the consumer has signaled (via `freed`) that
/// it is done with slot `i`. If the consumer is behind, the datagram is
/// still drained off the socket into a scratch buffer and explicitly
/// counted as a drop — never silently lost, and never overwriting a slot
/// still in use (the "reusing receive buffer after publication" trap).
pub struct ZeroCopyHandoffPipeline {
    socket: UdpSocket,
    slab_size: usize,
    datagram_size_hint: usize,
}

impl ZeroCopyHandoffPipeline {
    pub fn bind(port: u16, slab_size: usize) -> std::io::Result<Self> {
        Ok(ZeroCopyHandoffPipeline {
            socket: UdpSocket::bind(("127.0.0.1", port))?,
            slab_size,
            datagram_size_hint: 0,
        })
    }

    pub fn run(
        &mut self,
        message_count: u64,
        datagram_size: usize,
    ) -> std::io::Result<IngestResult> {
        self.datagram_size_hint = datagram_size;
        let slab = Arc::new(Slab::new(self.slab_size, datagram_size));
        let (ready_tx, ready_rx): (SyncSender<Option<usize>>, Receiver<Option<usize>>) =
            sync_channel(self.slab_size);
        let (freed_tx, freed_rx): (SyncSender<usize>, Receiver<usize>) =
            sync_channel(self.slab_size);
        let delivered = Arc::new(AtomicUsize::new(0));
        let corrupted = Arc::new(AtomicUsize::new(0));
        let delivered_c = delivered.clone();
        let corrupted_c = corrupted.clone();
        let slab_c = slab.clone();

        let consumer = thread::spawn(move || {
            while let Ok(Some(idx)) = ready_rx.recv() {
                // SAFETY: the receiver never reuses `idx` until this thread
                // sends it back on `freed_tx`, sent only after this read
                // completes — see the struct-level isolation note.
                let seq;
                let matches;
                unsafe {
                    let datagram = slab_c.get(idx);
                    seq = read_sequence(datagram);
                    matches = payload_matches(datagram, seq);
                }
                if matches {
                    delivered_c.fetch_add(1, Ordering::Relaxed);
                } else {
                    corrupted_c.fetch_add(1, Ordering::Relaxed);
                }
                let _ = freed_tx.send(idx);
            }
        });

        let mut in_flight = vec![false; self.slab_size];
        let mut discard_buf = vec![0u8; datagram_size];
        let mut application_dropped = 0u64;

        for i in 0..message_count {
            let idx = (i % self.slab_size as u64) as usize;
            // Reclaim any slots the consumer has finished with.
            while let Ok(freed) = freed_rx.try_recv() {
                in_flight[freed] = false;
            }
            if in_flight[idx] {
                self.socket.recv_from(&mut discard_buf)?;
                application_dropped += 1;
                continue;
            }
            // SAFETY: `in_flight[idx]` is false, meaning the consumer has
            // already finished with this slot (or it was never used) —
            // see the struct-level isolation note.
            unsafe {
                let target = slab.get_mut(idx);
                target.clear();
                target.resize(datagram_size, 0);
                let (len, _) = self.socket.recv_from(target)?;
                target.truncate(len);
            }
            in_flight[idx] = true;
            ready_tx
                .send(Some(idx))
                .expect("consumer thread exited early");
        }
        ready_tx.send(None).expect("consumer thread exited early");
        consumer.join().expect("consumer thread panicked");

        Ok(IngestResult {
            delivered: delivered.load(Ordering::Relaxed) as u64,
            application_dropped,
            corrupted: corrupted.load(Ordering::Relaxed) as u64,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn free_port() -> u16 {
        let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
        socket.local_addr().unwrap().port()
    }

    #[test]
    fn datagram_format_round_trips_sequence_and_payload() {
        for &size in &[64usize, 256, 1400] {
            for &seq in &[0u64, 1, 255, 256, 100_000] {
                let wire = build_datagram(seq, size);
                assert_eq!(wire.len(), size);
                assert_eq!(read_sequence(&wire), seq);
                assert!(payload_matches(&wire, seq));
            }
        }
    }

    #[test]
    fn raw_receive_kernel_validates_every_message() {
        let port = free_port();
        let datagram_size = 256;
        let message_count = 2000u64;
        let kernel = RawReceiveKernel::bind(port).unwrap();
        let sender = thread::spawn(move || {
            thread::sleep(Duration::from_millis(50));
            send_burst(port, message_count, datagram_size).unwrap();
        });
        let validated = kernel
            .receive_with_reused_buffer(message_count, datagram_size)
            .unwrap();
        sender.join().unwrap();
        assert_eq!(validated, message_count);
    }

    #[test]
    fn copying_handoff_delivers_every_message_with_ample_capacity() {
        let port = free_port();
        let datagram_size = 256;
        let message_count = 2000u64;
        let pipeline = CopyingHandoffPipeline::bind(port, 4096).unwrap();
        let sender = thread::spawn(move || {
            thread::sleep(Duration::from_millis(50));
            send_burst(port, message_count, datagram_size).unwrap();
        });
        let result = pipeline.run(message_count, datagram_size).unwrap();
        sender.join().unwrap();
        assert_eq!(result.accounted_for(), message_count);
        assert_eq!(result.corrupted, 0);
        assert_eq!(result.delivered, message_count);
        assert_eq!(result.application_dropped, 0);
    }

    #[test]
    fn copying_handoff_counts_explicit_drops_when_channel_is_undersized() {
        let port = free_port();
        let datagram_size = 64;
        let message_count = 5000u64;
        let pipeline = CopyingHandoffPipeline::bind(port, 4).unwrap();
        let sender = thread::spawn(move || {
            thread::sleep(Duration::from_millis(50));
            send_burst(port, message_count, datagram_size).unwrap();
        });
        let result = pipeline.run(message_count, datagram_size).unwrap();
        sender.join().unwrap();
        assert_eq!(result.accounted_for(), message_count);
        assert_eq!(result.corrupted, 0);
    }

    #[test]
    fn zero_copy_handoff_delivers_every_message_with_ample_slab() {
        let port = free_port();
        let datagram_size = 256;
        let message_count = 2000u64;
        let mut pipeline = ZeroCopyHandoffPipeline::bind(port, 4096).unwrap();
        let sender = thread::spawn(move || {
            thread::sleep(Duration::from_millis(50));
            send_burst(port, message_count, datagram_size).unwrap();
        });
        let result = pipeline.run(message_count, datagram_size).unwrap();
        sender.join().unwrap();
        assert_eq!(result.accounted_for(), message_count);
        assert_eq!(result.corrupted, 0);
        assert_eq!(result.delivered, message_count);
        assert_eq!(result.application_dropped, 0);
    }

    #[test]
    fn zero_copy_handoff_counts_explicit_drops_when_slab_is_undersized() {
        let port = free_port();
        let datagram_size = 64;
        let message_count = 5000u64;
        let mut pipeline = ZeroCopyHandoffPipeline::bind(port, 4).unwrap();
        let sender = thread::spawn(move || {
            thread::sleep(Duration::from_millis(50));
            send_burst(port, message_count, datagram_size).unwrap();
        });
        let result = pipeline.run(message_count, datagram_size).unwrap();
        sender.join().unwrap();
        assert_eq!(result.accounted_for(), message_count);
        assert_eq!(result.corrupted, 0);
    }
}
