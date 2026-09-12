//! Deterministic source-data generation and the five storage variants —
//! the cross-language equivalence contract
//! (../fixtures/ffm-memory-segments-fixtures.json). Every variant reads
//! the identical field values for a given dataset and reproduces the
//! identical checksum; storage location and access path change
//! ns/access and ns/record, never the result.
//!
//! Rust has no on-heap/off-heap distinction the way Java does — a
//! `Vec<T>` already IS native, GC-free, explicitly-owned memory, which
//! is what Java's `MemorySegment` exists to approximate. This crate
//! reframes the five variants honestly for that reality (rust.md):
//! `confined_segment`/`shared_segment` become a packed raw-byte buffer
//! (Rust's closest analog to a manually laid-out struct array) owned
//! either directly (single-owner, the "confined" analog) or through
//! `Arc` (shared, reference-counted — the real cost Rust pays instead of
//! a runtime confinement check); `copied_boundary_crossing` becomes
//! genuinely meaningful as "parse raw bytes (as if arrived over FFI or
//! network) into a native, typed representation," not a contrived
//! heap-to-heap copy. And this lab's "closing arena during use" trap is
//! structurally impossible to hit in Rust: there is no manual
//! close/lifetime step to get wrong — the borrow checker rejects any use
//! of a buffer after its owner is dropped, at compile time, before the
//! program ever runs.

use std::sync::Arc;

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

/// K deterministic pseudo-random indices in [0, n) — identical stream shared by every variant.
pub fn random_indices(n: usize, k: usize, seed: u64) -> Vec<usize> {
    let mut out = Vec::with_capacity(k);
    let mut x = seed;
    for _ in 0..k {
        x = xorshift64(x);
        out.push((x % n as u64) as usize);
    }
    out
}

pub trait RecordStorage {
    fn sequential_sum(&self) -> u64;
    fn random_access(&self, indices: &[usize]) -> u64;
}

// ---------------------------------------------------------------------
// fixedRecords: id(8) + value(8) + flag(4) + pad(4) = 24-byte stride
// ---------------------------------------------------------------------

pub mod fixed_records {
    use super::*;

    pub const N: usize = 500_000;
    const STRIDE: usize = 24;
    const HEADER_BYTES: usize = 4096;

    pub struct Source {
        pub id: Vec<u64>,
        pub value: Vec<u64>,
        pub flag: Vec<u32>,
    }

    pub fn generate(n: usize) -> Source {
        let mut id = Vec::with_capacity(n);
        let mut value = Vec::with_capacity(n);
        let mut flag = Vec::with_capacity(n);
        let mut x: u64 = 80;
        for i in 0..n {
            id.push(i as u64);
            x = xorshift64(x);
            value.push(x % 1_000_000);
            x = xorshift64(x);
            flag.push((x % 8) as u32);
        }
        Source { id, value, flag }
    }

    pub fn expected_checksum(s: &Source) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.id.len() {
            sum = sum
                .wrapping_add(s.id[i])
                .wrapping_add(s.value[i])
                .wrapping_add(s.flag[i] as u64);
        }
        sum
    }

    fn write_packed(s: &Source, header_bytes: usize) -> Vec<u8> {
        let n = s.id.len();
        let mut buf = vec![0u8; header_bytes + STRIDE * n];
        for i in 0..n {
            let base = header_bytes + i * STRIDE;
            buf[base..base + 8].copy_from_slice(&s.id[i].to_ne_bytes());
            buf[base + 8..base + 16].copy_from_slice(&s.value[i].to_ne_bytes());
            buf[base + 16..base + 20].copy_from_slice(&s.flag[i].to_ne_bytes());
        }
        buf
    }

    fn record_sum(buf: &[u8], base: usize) -> u64 {
        let id = u64::from_ne_bytes(buf[base..base + 8].try_into().unwrap());
        let value = u64::from_ne_bytes(buf[base + 8..base + 16].try_into().unwrap());
        let flag = u32::from_ne_bytes(buf[base + 16..base + 20].try_into().unwrap());
        id.wrapping_add(value).wrapping_add(flag as u64)
    }

    // ---- heapPrimitiveArray: struct-of-arrays, native Rust heap, no packing ----

    pub struct HeapPrimitiveArray {
        id: Vec<u64>,
        value: Vec<u64>,
        flag: Vec<u32>,
    }

    pub fn heap_primitive_array(s: &Source) -> HeapPrimitiveArray {
        HeapPrimitiveArray {
            id: s.id.clone(),
            value: s.value.clone(),
            flag: s.flag.clone(),
        }
    }

    impl RecordStorage for HeapPrimitiveArray {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.id.len() {
                sum = sum
                    .wrapping_add(self.id[i])
                    .wrapping_add(self.value[i])
                    .wrapping_add(self.flag[i] as u64);
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum
                    .wrapping_add(self.id[idx])
                    .wrapping_add(self.value[idx])
                    .wrapping_add(self.flag[idx] as u64);
            }
            sum
        }
    }

    // ---- confinedSegment: packed raw bytes, single owner ----

    pub struct ConfinedSegment {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn confined_segment(s: &Source) -> ConfinedSegment {
        ConfinedSegment {
            buf: write_packed(s, 0),
            n: s.id.len(),
        }
    }

    impl RecordStorage for ConfinedSegment {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(record_sum(&self.buf, i * STRIDE));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(record_sum(&self.buf, idx * STRIDE));
            }
            sum
        }
    }

    // ---- sharedSegment: the identical packed buffer, behind Arc ----

    pub struct SharedSegment {
        buf: Arc<Vec<u8>>,
        n: usize,
    }

    pub fn shared_segment(s: &Source) -> SharedSegment {
        SharedSegment {
            buf: Arc::new(write_packed(s, 0)),
            n: s.id.len(),
        }
    }

    impl RecordStorage for SharedSegment {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(record_sum(&self.buf, i * STRIDE));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(record_sum(&self.buf, idx * STRIDE));
            }
            sum
        }
    }

    // ---- slicedView: a nonzero-offset slice into a larger backing buffer ----

    pub struct SlicedView {
        backing: Vec<u8>,
        n: usize,
    }

    pub fn sliced_view(s: &Source) -> SlicedView {
        SlicedView {
            backing: write_packed(s, HEADER_BYTES),
            n: s.id.len(),
        }
    }

    impl RecordStorage for SlicedView {
        fn sequential_sum(&self) -> u64 {
            let slice = &self.backing[HEADER_BYTES..];
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(record_sum(slice, i * STRIDE));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let slice = &self.backing[HEADER_BYTES..];
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(record_sum(slice, idx * STRIDE));
            }
            sum
        }
    }

    // ---- copiedBoundaryCrossing: raw bytes "arrived from FFI", parsed into native Vecs every operation ----

    pub struct CopiedBoundaryCrossing {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn copied_boundary_crossing(s: &Source) -> CopiedBoundaryCrossing {
        CopiedBoundaryCrossing {
            buf: write_packed(s, 0),
            n: s.id.len(),
        }
    }

    impl RecordStorage for CopiedBoundaryCrossing {
        fn sequential_sum(&self) -> u64 {
            let mut id = vec![0u64; self.n];
            let mut value = vec![0u64; self.n];
            let mut flag = vec![0u32; self.n];
            for i in 0..self.n {
                let base = i * STRIDE;
                id[i] = u64::from_ne_bytes(self.buf[base..base + 8].try_into().unwrap());
                value[i] = u64::from_ne_bytes(self.buf[base + 8..base + 16].try_into().unwrap());
                flag[i] = u32::from_ne_bytes(self.buf[base + 16..base + 20].try_into().unwrap());
            }
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum
                    .wrapping_add(id[i])
                    .wrapping_add(value[i])
                    .wrapping_add(flag[i] as u64);
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut id = vec![0u64; self.n];
            let mut value = vec![0u64; self.n];
            let mut flag = vec![0u32; self.n];
            for i in 0..self.n {
                let base = i * STRIDE;
                id[i] = u64::from_ne_bytes(self.buf[base..base + 8].try_into().unwrap());
                value[i] = u64::from_ne_bytes(self.buf[base + 8..base + 16].try_into().unwrap());
                flag[i] = u32::from_ne_bytes(self.buf[base + 16..base + 20].try_into().unwrap());
            }
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum
                    .wrapping_add(id[idx])
                    .wrapping_add(value[idx])
                    .wrapping_add(flag[idx] as u64);
            }
            sum
        }
    }
}

// ---------------------------------------------------------------------
// largeNumericBuffers: one u64 field, 8-byte stride, N = 5,000,000
// ---------------------------------------------------------------------

pub mod large_numeric_buffers {
    use super::*;

    pub const N: usize = 5_000_000;
    const HEADER_BYTES: usize = 4096;

    pub fn generate(n: usize) -> Vec<u64> {
        let mut value = Vec::with_capacity(n);
        let mut x: u64 = 81;
        for _ in 0..n {
            x = xorshift64(x);
            value.push(x % 1_000_000_000);
        }
        value
    }

    pub fn expected_checksum(value: &[u64]) -> u64 {
        value.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
    }

    pub struct HeapPrimitiveArray {
        value: Vec<u64>,
    }

    pub fn heap_primitive_array(value: &[u64]) -> HeapPrimitiveArray {
        HeapPrimitiveArray {
            value: value.to_vec(),
        }
    }

    impl RecordStorage for HeapPrimitiveArray {
        fn sequential_sum(&self) -> u64 {
            self.value.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(self.value[idx]);
            }
            sum
        }
    }

    fn write_packed(value: &[u64], header_bytes: usize) -> Vec<u8> {
        let mut buf = vec![0u8; header_bytes + 8 * value.len()];
        for (i, &v) in value.iter().enumerate() {
            let base = header_bytes + i * 8;
            buf[base..base + 8].copy_from_slice(&v.to_ne_bytes());
        }
        buf
    }

    fn read_at(buf: &[u8], base: usize) -> u64 {
        u64::from_ne_bytes(buf[base..base + 8].try_into().unwrap())
    }

    pub struct ConfinedSegment {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn confined_segment(value: &[u64]) -> ConfinedSegment {
        ConfinedSegment {
            buf: write_packed(value, 0),
            n: value.len(),
        }
    }

    impl RecordStorage for ConfinedSegment {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(read_at(&self.buf, i * 8));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(read_at(&self.buf, idx * 8));
            }
            sum
        }
    }

    pub struct SharedSegment {
        buf: Arc<Vec<u8>>,
        n: usize,
    }

    pub fn shared_segment(value: &[u64]) -> SharedSegment {
        SharedSegment {
            buf: Arc::new(write_packed(value, 0)),
            n: value.len(),
        }
    }

    impl RecordStorage for SharedSegment {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(read_at(&self.buf, i * 8));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(read_at(&self.buf, idx * 8));
            }
            sum
        }
    }

    pub struct SlicedView {
        backing: Vec<u8>,
        n: usize,
    }

    pub fn sliced_view(value: &[u64]) -> SlicedView {
        SlicedView {
            backing: write_packed(value, HEADER_BYTES),
            n: value.len(),
        }
    }

    impl RecordStorage for SlicedView {
        fn sequential_sum(&self) -> u64 {
            let slice = &self.backing[HEADER_BYTES..];
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(read_at(slice, i * 8));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let slice = &self.backing[HEADER_BYTES..];
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(read_at(slice, idx * 8));
            }
            sum
        }
    }

    pub struct CopiedBoundaryCrossing {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn copied_boundary_crossing(value: &[u64]) -> CopiedBoundaryCrossing {
        CopiedBoundaryCrossing {
            buf: write_packed(value, 0),
            n: value.len(),
        }
    }

    impl RecordStorage for CopiedBoundaryCrossing {
        fn sequential_sum(&self) -> u64 {
            let mut copy = vec![0u64; self.n];
            for (i, slot) in copy.iter_mut().enumerate() {
                *slot = read_at(&self.buf, i * 8);
            }
            copy.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut copy = vec![0u64; self.n];
            for (i, slot) in copy.iter_mut().enumerate() {
                *slot = read_at(&self.buf, i * 8);
            }
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(copy[idx]);
            }
            sum
        }
    }
}

// ---------------------------------------------------------------------
// binaryFrames: length(4) + type(4) + payload(6 longs, 48B) = 56-byte stride
// ---------------------------------------------------------------------

pub mod binary_frames {
    use super::*;

    pub const N: usize = 100_000;
    pub const PAYLOAD_WORDS: usize = 6;
    pub const FRAME_LENGTH_FIELD: u32 = 48;
    const STRIDE: usize = 56;
    const HEADER_BYTES: usize = 4096;

    pub struct Source {
        pub frame_type: Vec<u32>,
        pub payload: Vec<[u64; PAYLOAD_WORDS]>,
    }

    pub fn generate(n: usize) -> Source {
        let mut frame_type = Vec::with_capacity(n);
        let mut payload = Vec::with_capacity(n);
        let mut x: u64 = 82;
        for _ in 0..n {
            x = xorshift64(x);
            frame_type.push((x % 4) as u32);
            let mut words = [0u64; PAYLOAD_WORDS];
            for w in words.iter_mut() {
                x = xorshift64(x);
                *w = x % 1_000_000;
            }
            payload.push(words);
        }
        Source {
            frame_type,
            payload,
        }
    }

    pub fn expected_checksum(s: &Source) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.frame_type.len() {
            sum = sum
                .wrapping_add(FRAME_LENGTH_FIELD as u64)
                .wrapping_add(s.frame_type[i] as u64);
            for &v in &s.payload[i] {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    fn write_packed(s: &Source, header_bytes: usize) -> Vec<u8> {
        let n = s.frame_type.len();
        let mut buf = vec![0u8; header_bytes + STRIDE * n];
        for i in 0..n {
            let base = header_bytes + i * STRIDE;
            buf[base..base + 4].copy_from_slice(&FRAME_LENGTH_FIELD.to_ne_bytes());
            buf[base + 4..base + 8].copy_from_slice(&s.frame_type[i].to_ne_bytes());
            for w in 0..PAYLOAD_WORDS {
                let off = base + 8 + w * 8;
                buf[off..off + 8].copy_from_slice(&s.payload[i][w].to_ne_bytes());
            }
        }
        buf
    }

    fn frame_sum(buf: &[u8], base: usize) -> u64 {
        let length = u32::from_ne_bytes(buf[base..base + 4].try_into().unwrap());
        let frame_type = u32::from_ne_bytes(buf[base + 4..base + 8].try_into().unwrap());
        let mut sum = (length as u64).wrapping_add(frame_type as u64);
        for w in 0..PAYLOAD_WORDS {
            let off = base + 8 + w * 8;
            sum = sum.wrapping_add(u64::from_ne_bytes(buf[off..off + 8].try_into().unwrap()));
        }
        sum
    }

    pub struct HeapPrimitiveArray {
        frame_type: Vec<u32>,
        payload: Vec<[u64; PAYLOAD_WORDS]>,
    }

    pub fn heap_primitive_array(s: &Source) -> HeapPrimitiveArray {
        HeapPrimitiveArray {
            frame_type: s.frame_type.clone(),
            payload: s.payload.clone(),
        }
    }

    impl RecordStorage for HeapPrimitiveArray {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.frame_type.len() {
                sum = sum
                    .wrapping_add(FRAME_LENGTH_FIELD as u64)
                    .wrapping_add(self.frame_type[i] as u64);
                for &v in &self.payload[i] {
                    sum = sum.wrapping_add(v);
                }
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum
                    .wrapping_add(FRAME_LENGTH_FIELD as u64)
                    .wrapping_add(self.frame_type[idx] as u64);
                for &v in &self.payload[idx] {
                    sum = sum.wrapping_add(v);
                }
            }
            sum
        }
    }

    pub struct ConfinedSegment {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn confined_segment(s: &Source) -> ConfinedSegment {
        ConfinedSegment {
            buf: write_packed(s, 0),
            n: s.frame_type.len(),
        }
    }

    impl RecordStorage for ConfinedSegment {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(frame_sum(&self.buf, i * STRIDE));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(frame_sum(&self.buf, idx * STRIDE));
            }
            sum
        }
    }

    pub struct SharedSegment {
        buf: Arc<Vec<u8>>,
        n: usize,
    }

    pub fn shared_segment(s: &Source) -> SharedSegment {
        SharedSegment {
            buf: Arc::new(write_packed(s, 0)),
            n: s.frame_type.len(),
        }
    }

    impl RecordStorage for SharedSegment {
        fn sequential_sum(&self) -> u64 {
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(frame_sum(&self.buf, i * STRIDE));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(frame_sum(&self.buf, idx * STRIDE));
            }
            sum
        }
    }

    pub struct SlicedView {
        backing: Vec<u8>,
        n: usize,
    }

    pub fn sliced_view(s: &Source) -> SlicedView {
        SlicedView {
            backing: write_packed(s, HEADER_BYTES),
            n: s.frame_type.len(),
        }
    }

    impl RecordStorage for SlicedView {
        fn sequential_sum(&self) -> u64 {
            let slice = &self.backing[HEADER_BYTES..];
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum.wrapping_add(frame_sum(slice, i * STRIDE));
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let slice = &self.backing[HEADER_BYTES..];
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum.wrapping_add(frame_sum(slice, idx * STRIDE));
            }
            sum
        }
    }

    pub struct CopiedBoundaryCrossing {
        buf: Vec<u8>,
        n: usize,
    }

    pub fn copied_boundary_crossing(s: &Source) -> CopiedBoundaryCrossing {
        CopiedBoundaryCrossing {
            buf: write_packed(s, 0),
            n: s.frame_type.len(),
        }
    }

    fn parse(buf: &[u8], n: usize) -> (Vec<u32>, Vec<[u64; PAYLOAD_WORDS]>) {
        let mut frame_type = vec![0u32; n];
        let mut payload = vec![[0u64; PAYLOAD_WORDS]; n];
        for i in 0..n {
            let base = i * STRIDE;
            frame_type[i] = u32::from_ne_bytes(buf[base + 4..base + 8].try_into().unwrap());
            for w in 0..PAYLOAD_WORDS {
                let off = base + 8 + w * 8;
                payload[i][w] = u64::from_ne_bytes(buf[off..off + 8].try_into().unwrap());
            }
        }
        (frame_type, payload)
    }

    impl RecordStorage for CopiedBoundaryCrossing {
        fn sequential_sum(&self) -> u64 {
            let (frame_type, payload) = parse(&self.buf, self.n);
            let mut sum = 0u64;
            for i in 0..self.n {
                sum = sum
                    .wrapping_add(FRAME_LENGTH_FIELD as u64)
                    .wrapping_add(frame_type[i] as u64);
                for &v in &payload[i] {
                    sum = sum.wrapping_add(v);
                }
            }
            sum
        }
        fn random_access(&self, indices: &[usize]) -> u64 {
            let (frame_type, payload) = parse(&self.buf, self.n);
            let mut sum = 0u64;
            for &idx in indices {
                sum = sum
                    .wrapping_add(FRAME_LENGTH_FIELD as u64)
                    .wrapping_add(frame_type[idx] as u64);
                for &v in &payload[idx] {
                    sum = sum.wrapping_add(v);
                }
            }
            sum
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_records_all_variants_agree() {
        let s = fixed_records::generate(fixed_records::N);
        assert_eq!(s.id[0], 0);
        assert_eq!(s.value[0], 580_880);
        assert_eq!(s.flag[0], 0);

        let expected = fixed_records::expected_checksum(&s);
        assert_eq!(expected, 375_147_506_089);

        assert_eq!(
            fixed_records::heap_primitive_array(&s).sequential_sum(),
            expected
        );
        assert_eq!(
            fixed_records::confined_segment(&s).sequential_sum(),
            expected
        );
        assert_eq!(fixed_records::shared_segment(&s).sequential_sum(), expected);
        assert_eq!(fixed_records::sliced_view(&s).sequential_sum(), expected);
        assert_eq!(
            fixed_records::copied_boundary_crossing(&s).sequential_sum(),
            expected
        );

        let indices = random_indices(fixed_records::N, 500, 900);
        let expected_random = fixed_records::heap_primitive_array(&s).random_access(&indices);
        assert_eq!(
            fixed_records::confined_segment(&s).random_access(&indices),
            expected_random
        );
        assert_eq!(
            fixed_records::shared_segment(&s).random_access(&indices),
            expected_random
        );
        assert_eq!(
            fixed_records::sliced_view(&s).random_access(&indices),
            expected_random
        );
        assert_eq!(
            fixed_records::copied_boundary_crossing(&s).random_access(&indices),
            expected_random
        );
    }

    #[test]
    fn large_numeric_buffers_all_variants_agree() {
        let value = large_numeric_buffers::generate(large_numeric_buffers::N);
        assert_eq!(value[0], 646_811_153);

        let expected = large_numeric_buffers::expected_checksum(&value);
        assert_eq!(expected, 2_499_690_517_128_328);

        assert_eq!(
            large_numeric_buffers::heap_primitive_array(&value).sequential_sum(),
            expected
        );
        assert_eq!(
            large_numeric_buffers::confined_segment(&value).sequential_sum(),
            expected
        );
        assert_eq!(
            large_numeric_buffers::shared_segment(&value).sequential_sum(),
            expected
        );
        assert_eq!(
            large_numeric_buffers::sliced_view(&value).sequential_sum(),
            expected
        );
        assert_eq!(
            large_numeric_buffers::copied_boundary_crossing(&value).sequential_sum(),
            expected
        );

        let indices = random_indices(large_numeric_buffers::N, 500, 901);
        let expected_random =
            large_numeric_buffers::heap_primitive_array(&value).random_access(&indices);
        assert_eq!(
            large_numeric_buffers::sliced_view(&value).random_access(&indices),
            expected_random
        );
    }

    #[test]
    fn binary_frames_all_variants_agree() {
        let s = binary_frames::generate(binary_frames::N);
        assert_eq!(s.frame_type[0], 2);
        assert_eq!(s.payload[0][0], 724_347);

        let expected = binary_frames::expected_checksum(&s);
        assert_eq!(expected, 300_295_670_129);

        assert_eq!(
            binary_frames::heap_primitive_array(&s).sequential_sum(),
            expected
        );
        assert_eq!(
            binary_frames::confined_segment(&s).sequential_sum(),
            expected
        );
        assert_eq!(binary_frames::shared_segment(&s).sequential_sum(), expected);
        assert_eq!(binary_frames::sliced_view(&s).sequential_sum(), expected);
        assert_eq!(
            binary_frames::copied_boundary_crossing(&s).sequential_sum(),
            expected
        );

        let indices = random_indices(binary_frames::N, 500, 902);
        let expected_random = binary_frames::heap_primitive_array(&s).random_access(&indices);
        assert_eq!(
            binary_frames::confined_segment(&s).random_access(&indices),
            expected_random
        );
        assert_eq!(
            binary_frames::shared_segment(&s).random_access(&indices),
            expected_random
        );
        assert_eq!(
            binary_frames::sliced_view(&s).random_access(&indices),
            expected_random
        );
        assert_eq!(
            binary_frames::copied_boundary_crossing(&s).random_access(&indices),
            expected_random
        );
    }
}
