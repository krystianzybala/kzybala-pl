//! Fixed 64-byte record file format shared with the Java side (identical
//! derivation formulas — see `code/fixtures/memory-mapped-files-fixtures.json`)
//! and the mmap/buffered variants built on top of it.

use memmap2::{Mmap, MmapMut, MmapOptions};
use std::fs::{File, OpenOptions};
use std::io::{Read, Result as IoResult, Write};
use std::path::Path;

pub const RECORD_SIZE: usize = 64;
pub const PAYLOAD_SIZE: usize = RECORD_SIZE - 16;

pub fn expected_value(id: u64) -> u64 {
    id.wrapping_mul(2_654_435_761).wrapping_add(1)
}

pub fn expected_payload_byte(id: u64, index: usize) -> u8 {
    (id.wrapping_add(index as u64) & 0xFF) as u8
}

pub fn checksum_record(id: u64, value: u64, payload: &[u8]) -> u64 {
    let mut h = id.wrapping_mul(31).wrapping_add(value);
    for &b in payload {
        h = h.wrapping_mul(31).wrapping_add(b as u64);
    }
    h
}

fn write_record(buf: &mut [u8], id: u64) {
    let value = expected_value(id);
    buf[0..8].copy_from_slice(&id.to_le_bytes());
    buf[8..16].copy_from_slice(&value.to_le_bytes());
    for i in 0..PAYLOAD_SIZE {
        buf[16 + i] = expected_payload_byte(id, i);
    }
}

/// Writes `record_count` deterministic fixed records to `path`, creating/truncating it.
pub fn write_records(path: &Path, record_count: u64) -> IoResult<()> {
    let mut file = File::create(path)?;
    let mut buf = [0u8; RECORD_SIZE];
    for id in 0..record_count {
        write_record(&mut buf, id);
        file.write_all(&buf)?;
    }
    Ok(())
}

fn decode_record(buf: &[u8]) -> (u64, u64, &[u8]) {
    let id = u64::from_le_bytes(buf[0..8].try_into().unwrap());
    let value = u64::from_le_bytes(buf[8..16].try_into().unwrap());
    let payload = &buf[16..RECORD_SIZE];
    (id, value, payload)
}

/// Variant: buffered read — every record is copied from the OS page cache
/// into a stack buffer via `Read::read_exact`.
pub fn buffered_read_checksum(path: &Path, record_count: u64) -> IoResult<u64> {
    let mut file = File::open(path)?;
    let mut buf = [0u8; RECORD_SIZE];
    let mut checksum = 0u64;
    for _ in 0..record_count {
        file.read_exact(&mut buf)?;
        let (id, value, payload) = decode_record(&buf);
        checksum = checksum.wrapping_add(checksum_record(id, value, payload));
    }
    Ok(checksum)
}

/// Opens a read-only memory map over `path`.
///
/// # Safety / isolation note
/// `Mmap::map` is `unsafe` because the file could be truncated or mutated by
/// another process while mapped, which would turn subsequent reads into
/// undefined behavior rather than a normal I/O error. This lab's benchmark
/// and test harnesses never mutate a file while it is mapped for reading
/// elsewhere, and this function is the ONLY place in this crate that calls
/// the unsafe mapping constructor — every other function in this module
/// only ever touches the resulting safe `&[u8]`/`&mut [u8]` view.
pub fn map_readonly(path: &Path) -> IoResult<Mmap> {
    let file = File::open(path)?;
    // SAFETY: see the isolation note above — file lifetime is scoped to this
    // process's own test/benchmark fixtures, never concurrently mutated.
    unsafe { Mmap::map(&file) }
}

/// Variant: mmap sequential read.
pub fn mmap_sequential_checksum(mapped: &Mmap, record_count: u64) -> u64 {
    let mut checksum = 0u64;
    for i in 0..record_count as usize {
        let off = i * RECORD_SIZE;
        let (id, value, payload) = decode_record(&mapped[off..off + RECORD_SIZE]);
        checksum = checksum.wrapping_add(checksum_record(id, value, payload));
    }
    checksum
}

/// Variant: mmap random access — reads records in the order given by `order`.
pub fn mmap_random_checksum(mapped: &Mmap, order: &[u32]) -> u64 {
    let mut checksum = 0u64;
    for &idx in order {
        let off = idx as usize * RECORD_SIZE;
        let (id, value, payload) = decode_record(&mapped[off..off + RECORD_SIZE]);
        checksum = checksum.wrapping_add(checksum_record(id, value, payload));
    }
    checksum
}

/// Deterministic pseudo-random permutation of [0, count) — same order every
/// run, identical algorithm to `MmapReadKernel.deterministicPermutation` on
/// the Java side (a Lehmer/LCG-driven Fisher-Yates shuffle).
pub fn deterministic_permutation(count: u32, seed: u64) -> Vec<u32> {
    let mut order: Vec<u32> = (0..count).collect();
    let mut state = seed;
    let mut i = count;
    while i > 1 {
        i -= 1;
        state = state
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        let j = ((state >> 33) % (i as u64 + 1)) as usize;
        order.swap(i as usize, j);
    }
    order
}

/// Creates (or truncates) `path` to `file_size` bytes and maps it read-write.
///
/// # Safety / isolation note
/// Same constraint as [`map_readonly`]: `MmapMut::map_mut` is unsafe because
/// concurrent external mutation of the backing file is undefined behavior.
/// This is the only unsafe call site for the write path in this crate.
pub fn map_readwrite(path: &Path, file_size: u64) -> IoResult<MmapMut> {
    let file = OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(true)
        .open(path)?;
    file.set_len(file_size)?;
    // SAFETY: see the isolation note above.
    unsafe { MmapOptions::new().map_mut(&file) }
}

/// Writes every record's bytes into the mapping. Does not flush.
pub fn write_all_records(mapped: &mut MmapMut, record_count: u64) {
    for id in 0..record_count {
        let off = id as usize * RECORD_SIZE;
        write_record(&mut mapped[off..off + RECORD_SIZE], id);
    }
}

/// Forces every dirty page in the mapping to storage — the cost this variant isolates.
pub fn flush(mapped: &MmapMut) -> IoResult<()> {
    mapped.flush()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    const RECORD_COUNT: u64 = 4096;
    const FILE_SIZE: u64 = RECORD_COUNT * RECORD_SIZE as u64;

    fn expected_checksum() -> u64 {
        let mut checksum = 0u64;
        for id in 0..RECORD_COUNT {
            let value = expected_value(id);
            let payload: Vec<u8> = (0..PAYLOAD_SIZE as u64)
                .map(|i| expected_payload_byte(id, i as usize))
                .collect();
            checksum = checksum.wrapping_add(checksum_record(id, value, &payload));
        }
        checksum
    }

    #[test]
    fn buffered_read_matches_expected_checksum() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("records.bin");
        write_records(&path, RECORD_COUNT).unwrap();
        let checksum = buffered_read_checksum(&path, RECORD_COUNT).unwrap();
        assert_eq!(checksum, expected_checksum());
    }

    #[test]
    fn mmap_sequential_matches_expected_checksum() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("records.bin");
        write_records(&path, RECORD_COUNT).unwrap();
        let mapped = map_readonly(&path).unwrap();
        let checksum = mmap_sequential_checksum(&mapped, RECORD_COUNT);
        assert_eq!(checksum, expected_checksum());
    }

    #[test]
    fn mmap_random_matches_expected_checksum() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("records.bin");
        write_records(&path, RECORD_COUNT).unwrap();
        let mapped = map_readonly(&path).unwrap();
        let order = deterministic_permutation(RECORD_COUNT as u32, 424_242);
        let checksum = mmap_random_checksum(&mapped, &order);
        assert_eq!(checksum, expected_checksum());
    }

    #[test]
    fn permutation_visits_every_index_exactly_once() {
        let order = deterministic_permutation(4096, 424_242);
        let mut seen = vec![false; 4096];
        for &idx in &order {
            assert!(!seen[idx as usize], "index {idx} visited twice");
            seen[idx as usize] = true;
        }
        assert!(seen.iter().all(|&s| s));
    }

    #[test]
    fn permutation_is_deterministic_across_calls() {
        let a = deterministic_permutation(4096, 424_242);
        let b = deterministic_permutation(4096, 424_242);
        assert_eq!(a, b);
    }

    #[test]
    fn mmap_write_and_flush_produces_readable_records() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("written.bin");
        {
            let mut mapped = map_readwrite(&path, FILE_SIZE).unwrap();
            write_all_records(&mut mapped, RECORD_COUNT);
            flush(&mapped).unwrap();
        }
        let read_back = map_readonly(&path).unwrap();
        let checksum = mmap_sequential_checksum(&read_back, RECORD_COUNT);
        assert_eq!(checksum, expected_checksum());
    }
}
