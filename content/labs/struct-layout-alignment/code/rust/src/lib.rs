//! Deterministic source-data generation and the five layout variants —
//! the cross-language equivalence contract
//! (../fixtures/struct-layout-alignment-fixtures.json). Every variant
//! reads the identical field values for a given dataset and reproduces
//! the identical checksum (or, for `producer_consumer_counters`, the
//! identical final counts); field order and alignment change
//! bytes/record and ns/access, never the result.
//!
//! Rust's DEFAULT struct layout (no `repr` attribute) is, like the
//! JVM's default object layout, UNSPECIFIED — the compiler is free to
//! reorder fields for its own packing, exactly the reason this lab's
//! "assuming field declaration order is universal" trap applies to both
//! languages' *default* representations equally. `#[repr(C)]` is what
//! makes Rust respect declaration order exactly, the direct analog of
//! this lab's Java `MemoryLayout.structLayout` (which never reorders
//! either) — see `mixed_record::Natural` below, verified empirically to
//! NOT match its declared order's naive byte count (rust.md).

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub mod mixed_record {
    use super::xorshift64;

    pub const N: usize = 500_000;

    pub struct Source {
        pub active: Vec<u8>,
        pub timestamp: Vec<u64>,
        pub category: Vec<u8>,
        pub quantity: Vec<u32>,
        pub flags: Vec<u16>,
        pub amount_ticks: Vec<u64>,
    }

    pub fn generate(n: usize) -> Source {
        let mut active = Vec::with_capacity(n);
        let mut timestamp = Vec::with_capacity(n);
        let mut category = Vec::with_capacity(n);
        let mut quantity = Vec::with_capacity(n);
        let mut flags = Vec::with_capacity(n);
        let mut amount_ticks = Vec::with_capacity(n);
        let mut x: u64 = 90;
        for i in 0..n {
            x = xorshift64(x);
            active.push((x % 2) as u8);
            x = xorshift64(x);
            category.push((x % 16) as u8);
            x = xorshift64(x);
            quantity.push((x % 100_000) as u32);
            x = xorshift64(x);
            flags.push((x % 1_000) as u16);
            x = xorshift64(x);
            amount_ticks.push(x % 10_000_000);
            timestamp.push(i as u64);
        }
        Source {
            active,
            timestamp,
            category,
            quantity,
            flags,
            amount_ticks,
        }
    }

    pub fn expected_checksum(s: &Source) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.active.len() {
            sum = sum
                .wrapping_add(s.active[i] as u64)
                .wrapping_add(s.timestamp[i])
                .wrapping_add(s.category[i] as u64)
                .wrapping_add(s.quantity[i] as u64)
                .wrapping_add(s.flags[i] as u64)
                .wrapping_add(s.amount_ticks[i]);
        }
        sum
    }

    /// Declared in a plausible "business" order, but with NO `repr`
    /// attribute — the compiler is free to reorder these fields however
    /// it likes, exactly like the JVM's default object layout. Its real
    /// `size_of` is measured, never assumed, in this crate's tests.
    pub struct Natural {
        pub timestamp: u64,
        pub active: u8,
        pub category: u8,
        pub amount_ticks: u64,
        pub quantity: u32,
        pub flags: u16,
    }

    /// `#[repr(C)]`: declaration order is now a hard guarantee. This
    /// order is deliberately adversarial (alternating small/large
    /// fields) to maximize padding — matches the Java track's
    /// hand-computed 40-byte layout exactly, byte for byte.
    #[repr(C)]
    pub struct PoorFieldOrder {
        pub active: u8,
        pub timestamp: u64,
        pub category: u8,
        pub amount_ticks: u64,
        pub flags: u16,
        pub quantity: u32,
    }

    /// `#[repr(C)]`, widest-first — zero padding, 24 bytes exactly,
    /// matching the Java track's optimized layout byte for byte.
    #[repr(C)]
    pub struct OptimizedFieldOrder {
        pub amount_ticks: u64,
        pub timestamp: u64,
        pub quantity: u32,
        pub flags: u16,
        pub active: u8,
        pub category: u8,
    }

    /// The optimized 24-byte layout, plus `align(64)` — the compiler
    /// pads every instance/array-element to a full cache line
    /// automatically; no manual trailing-padding arithmetic needed,
    /// unlike the Java track's explicit segment-alignment call
    /// (rust.md).
    #[repr(C, align(64))]
    pub struct CacheLineAligned {
        pub amount_ticks: u64,
        pub timestamp: u64,
        pub quantity: u32,
        pub flags: u16,
        pub active: u8,
        pub category: u8,
    }

    /// `#[repr(C, packed)]`: zero padding, ANY alignment. `timestamp`
    /// happens to land at offset 0 (aligned by luck), but
    /// `amount_ticks`(offset 10) and `quantity`(offset 18) do not —
    /// matching the Java track's unaligned offsets exactly. Reading a
    /// `Copy` field OUT BY VALUE (`let v = s.field;`) from a packed
    /// struct is safe Rust (the compiler emits an unaligned load); only
    /// taking a REFERENCE to a misaligned field (`&s.field`) is
    /// rejected — this lab's `sum` functions below never do that, so
    /// this module contains zero `unsafe` blocks (rust.md).
    #[repr(C, packed)]
    pub struct PackedUnaligned {
        pub timestamp: u64,
        pub active: u8,
        pub category: u8,
        pub amount_ticks: u64,
        pub quantity: u32,
        pub flags: u16,
    }

    fn build<T>(s: &Source, mut make: impl FnMut(u64, u8, u8, u32, u16, u64) -> T) -> Vec<T> {
        (0..s.active.len())
            .map(|i| {
                make(
                    s.timestamp[i],
                    s.active[i],
                    s.category[i],
                    s.quantity[i],
                    s.flags[i],
                    s.amount_ticks[i],
                )
            })
            .collect()
    }

    pub fn build_natural(s: &Source) -> Vec<Natural> {
        build(
            s,
            |timestamp, active, category, quantity, flags, amount_ticks| Natural {
                timestamp,
                active,
                category,
                amount_ticks,
                quantity,
                flags,
            },
        )
    }

    pub fn sum_natural(records: &[Natural]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.active as u64)
                .wrapping_add(r.timestamp)
                .wrapping_add(r.category as u64)
                .wrapping_add(r.quantity as u64)
                .wrapping_add(r.flags as u64)
                .wrapping_add(r.amount_ticks);
        }
        sum
    }

    pub fn build_poor_field_order(s: &Source) -> Vec<PoorFieldOrder> {
        build(
            s,
            |timestamp, active, category, quantity, flags, amount_ticks| PoorFieldOrder {
                active,
                timestamp,
                category,
                amount_ticks,
                flags,
                quantity,
            },
        )
    }

    pub fn sum_poor_field_order(records: &[PoorFieldOrder]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.active as u64)
                .wrapping_add(r.timestamp)
                .wrapping_add(r.category as u64)
                .wrapping_add(r.quantity as u64)
                .wrapping_add(r.flags as u64)
                .wrapping_add(r.amount_ticks);
        }
        sum
    }

    pub fn build_optimized_field_order(s: &Source) -> Vec<OptimizedFieldOrder> {
        build(
            s,
            |timestamp, active, category, quantity, flags, amount_ticks| OptimizedFieldOrder {
                amount_ticks,
                timestamp,
                quantity,
                flags,
                active,
                category,
            },
        )
    }

    pub fn sum_optimized_field_order(records: &[OptimizedFieldOrder]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.active as u64)
                .wrapping_add(r.timestamp)
                .wrapping_add(r.category as u64)
                .wrapping_add(r.quantity as u64)
                .wrapping_add(r.flags as u64)
                .wrapping_add(r.amount_ticks);
        }
        sum
    }

    pub fn build_cache_line_aligned(s: &Source) -> Vec<CacheLineAligned> {
        build(
            s,
            |timestamp, active, category, quantity, flags, amount_ticks| CacheLineAligned {
                amount_ticks,
                timestamp,
                quantity,
                flags,
                active,
                category,
            },
        )
    }

    pub fn sum_cache_line_aligned(records: &[CacheLineAligned]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.active as u64)
                .wrapping_add(r.timestamp)
                .wrapping_add(r.category as u64)
                .wrapping_add(r.quantity as u64)
                .wrapping_add(r.flags as u64)
                .wrapping_add(r.amount_ticks);
        }
        sum
    }

    pub fn build_packed_unaligned(s: &Source) -> Vec<PackedUnaligned> {
        build(
            s,
            |timestamp, active, category, quantity, flags, amount_ticks| PackedUnaligned {
                timestamp,
                active,
                category,
                amount_ticks,
                quantity,
                flags,
            },
        )
    }

    pub fn sum_packed_unaligned(records: &[PackedUnaligned]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            // Every access below reads a Copy field BY VALUE — safe, no
            // unsafe block, even though several fields sit at unaligned
            // offsets (class doc).
            let timestamp = r.timestamp;
            let active = r.active;
            let category = r.category;
            let amount_ticks = r.amount_ticks;
            let quantity = r.quantity;
            let flags = r.flags;
            sum = sum
                .wrapping_add(active as u64)
                .wrapping_add(timestamp)
                .wrapping_add(category as u64)
                .wrapping_add(quantity as u64)
                .wrapping_add(flags as u64)
                .wrapping_add(amount_ticks);
        }
        sum
    }
}

pub mod header_payload {
    use super::xorshift64;

    pub const N: usize = 200_000;
    pub const PAYLOAD_WORDS: usize = 8;

    pub struct Source {
        pub msg_type: Vec<u8>,
        pub msg_flags: Vec<u8>,
        pub sequence: Vec<u32>,
        pub payload: Vec<[u64; PAYLOAD_WORDS]>,
    }

    pub fn generate(n: usize) -> Source {
        let mut msg_type = Vec::with_capacity(n);
        let mut msg_flags = Vec::with_capacity(n);
        let mut sequence = Vec::with_capacity(n);
        let mut payload = Vec::with_capacity(n);
        let mut x: u64 = 91;
        for _ in 0..n {
            x = xorshift64(x);
            msg_type.push((x % 8) as u8);
            x = xorshift64(x);
            msg_flags.push((x % 256) as u8);
            x = xorshift64(x);
            sequence.push((x % 1_000_000) as u32);
            let mut words = [0u64; PAYLOAD_WORDS];
            for w in words.iter_mut() {
                x = xorshift64(x);
                *w = x % 1_000_000;
            }
            payload.push(words);
        }
        Source {
            msg_type,
            msg_flags,
            sequence,
            payload,
        }
    }

    pub fn expected_checksum(s: &Source) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.msg_type.len() {
            sum = sum
                .wrapping_add(s.msg_type[i] as u64)
                .wrapping_add(s.msg_flags[i] as u64)
                .wrapping_add(s.sequence[i] as u64);
            for &v in &s.payload[i] {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    pub struct Natural {
        pub msg_type: u8,
        pub msg_flags: u8,
        pub sequence: u32,
        pub payload: [u64; PAYLOAD_WORDS],
    }

    #[repr(C)]
    pub struct PoorFieldOrder {
        pub msg_flags: u8,
        pub sequence: u32,
        pub msg_type: u8,
        pub payload: [u64; PAYLOAD_WORDS],
    }

    #[repr(C)]
    pub struct OptimizedFieldOrder {
        pub sequence: u32,
        pub msg_type: u8,
        pub msg_flags: u8,
        pub payload: [u64; PAYLOAD_WORDS],
    }

    /// Explicit padding pushes `payload` to offset 64 — its own
    /// dedicated cache line, never split across two — plus
    /// `align(64)` so every array element itself starts on a cache-line
    /// boundary too.
    #[repr(C, align(64))]
    pub struct CacheLineAligned {
        pub msg_type: u8,
        pub msg_flags: u8,
        pub sequence: u32,
        _pad: [u8; 56],
        pub payload: [u64; PAYLOAD_WORDS],
    }

    #[repr(C, packed)]
    pub struct PackedUnaligned {
        pub msg_type: u8,
        pub msg_flags: u8,
        pub sequence: u32,
        pub payload: [u64; PAYLOAD_WORDS],
    }

    pub fn build_natural(s: &Source) -> Vec<Natural> {
        (0..s.msg_type.len())
            .map(|i| Natural {
                msg_type: s.msg_type[i],
                msg_flags: s.msg_flags[i],
                sequence: s.sequence[i],
                payload: s.payload[i],
            })
            .collect()
    }

    pub fn sum_natural(records: &[Natural]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.msg_type as u64)
                .wrapping_add(r.msg_flags as u64)
                .wrapping_add(r.sequence as u64);
            for &v in &r.payload {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    pub fn build_poor_field_order(s: &Source) -> Vec<PoorFieldOrder> {
        (0..s.msg_type.len())
            .map(|i| PoorFieldOrder {
                msg_flags: s.msg_flags[i],
                sequence: s.sequence[i],
                msg_type: s.msg_type[i],
                payload: s.payload[i],
            })
            .collect()
    }

    pub fn sum_poor_field_order(records: &[PoorFieldOrder]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.msg_type as u64)
                .wrapping_add(r.msg_flags as u64)
                .wrapping_add(r.sequence as u64);
            for &v in &r.payload {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    pub fn build_optimized_field_order(s: &Source) -> Vec<OptimizedFieldOrder> {
        (0..s.msg_type.len())
            .map(|i| OptimizedFieldOrder {
                sequence: s.sequence[i],
                msg_type: s.msg_type[i],
                msg_flags: s.msg_flags[i],
                payload: s.payload[i],
            })
            .collect()
    }

    pub fn sum_optimized_field_order(records: &[OptimizedFieldOrder]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.msg_type as u64)
                .wrapping_add(r.msg_flags as u64)
                .wrapping_add(r.sequence as u64);
            for &v in &r.payload {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    pub fn build_cache_line_aligned(s: &Source) -> Vec<CacheLineAligned> {
        (0..s.msg_type.len())
            .map(|i| CacheLineAligned {
                msg_type: s.msg_type[i],
                msg_flags: s.msg_flags[i],
                sequence: s.sequence[i],
                _pad: [0u8; 56],
                payload: s.payload[i],
            })
            .collect()
    }

    pub fn sum_cache_line_aligned(records: &[CacheLineAligned]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            sum = sum
                .wrapping_add(r.msg_type as u64)
                .wrapping_add(r.msg_flags as u64)
                .wrapping_add(r.sequence as u64);
            for &v in &r.payload {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    pub fn build_packed_unaligned(s: &Source) -> Vec<PackedUnaligned> {
        (0..s.msg_type.len())
            .map(|i| PackedUnaligned {
                msg_type: s.msg_type[i],
                msg_flags: s.msg_flags[i],
                sequence: s.sequence[i],
                payload: s.payload[i],
            })
            .collect()
    }

    pub fn sum_packed_unaligned(records: &[PackedUnaligned]) -> u64 {
        let mut sum = 0u64;
        for r in records {
            let msg_type = r.msg_type;
            let msg_flags = r.msg_flags;
            let sequence = r.sequence;
            // The whole array is copied out by value first (arrays of Copy
            // types are Copy) — this avoids ever forming a reference into
            // the packed struct's unaligned array field.
            let payload: [u64; PAYLOAD_WORDS] = r.payload;
            sum = sum
                .wrapping_add(msg_type as u64)
                .wrapping_add(msg_flags as u64)
                .wrapping_add(sequence as u64);
            for v in payload {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }
}

pub mod producer_consumer_counters {
    use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};

    pub const INCREMENTS_PER_THREAD: u64 = 5_000_000;

    /// `#[repr(C)]` GUARANTEES the two counters stay adjacent — this
    /// crate wants that adjacency deliberately, not left to the
    /// compiler's discretion, matching content/labs/false-sharing's own
    /// `SharedCounters` convention.
    #[repr(C)]
    pub struct Natural {
        pub producer_count: AtomicU64,
        pub consumer_count: AtomicU64,
    }

    impl Default for Natural {
        fn default() -> Self {
            Self::new()
        }
    }

    impl Natural {
        pub fn new() -> Self {
            Self {
                producer_count: AtomicU64::new(0),
                consumer_count: AtomicU64::new(0),
            }
        }
    }

    #[repr(C)]
    pub struct PoorFieldOrder {
        pub producer_count: AtomicU64,
        pub cold_a: u32,
        pub cold_b: u32,
        pub cold_c: u32,
        pub consumer_count: AtomicU64,
    }

    impl Default for PoorFieldOrder {
        fn default() -> Self {
            Self::new()
        }
    }

    impl PoorFieldOrder {
        pub fn new() -> Self {
            Self {
                producer_count: AtomicU64::new(0),
                cold_a: 0,
                cold_b: 0,
                cold_c: 0,
                consumer_count: AtomicU64::new(0),
            }
        }
    }

    #[repr(C)]
    pub struct OptimizedFieldOrder {
        pub producer_count: AtomicU64,
        pub consumer_count: AtomicU64,
        pub cold_a: u32,
        pub cold_b: u32,
    }

    impl Default for OptimizedFieldOrder {
        fn default() -> Self {
            Self::new()
        }
    }

    impl OptimizedFieldOrder {
        pub fn new() -> Self {
            Self {
                producer_count: AtomicU64::new(0),
                consumer_count: AtomicU64::new(0),
                cold_a: 0,
                cold_b: 0,
            }
        }
    }

    /// The same `align(64)` wrapper technique as
    /// content/labs/false-sharing's `CacheLineAligned<T>` — an
    /// alignment guarantee the compiler enforces, which manual padding
    /// fields cannot be optimized away from, because alignment is part
    /// of the type's layout contract.
    #[repr(align(64))]
    pub struct CacheLineAlignedCell<T>(pub T);

    pub struct CacheLineAligned {
        pub producer_count: CacheLineAlignedCell<AtomicU64>,
        pub consumer_count: CacheLineAlignedCell<AtomicU64>,
    }

    impl Default for CacheLineAligned {
        fn default() -> Self {
            Self::new()
        }
    }

    impl CacheLineAligned {
        pub fn new() -> Self {
            Self {
                producer_count: CacheLineAlignedCell(AtomicU64::new(0)),
                consumer_count: CacheLineAlignedCell(AtomicU64::new(0)),
            }
        }
    }

    /// Both counters packed into adjacent 4-byte atomics — guaranteed
    /// same cache line, likely the same word.
    #[repr(C)]
    pub struct PackedUnaligned {
        pub producer_count: AtomicU32,
        pub consumer_count: AtomicU32,
    }

    impl Default for PackedUnaligned {
        fn default() -> Self {
            Self::new()
        }
    }

    impl PackedUnaligned {
        pub fn new() -> Self {
            Self {
                producer_count: AtomicU32::new(0),
                consumer_count: AtomicU32::new(0),
            }
        }
    }

    pub fn produce_natural(c: &Natural) {
        c.producer_count.fetch_add(1, Ordering::Relaxed);
    }
    pub fn consume_natural(c: &Natural) {
        c.consumer_count.fetch_add(1, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::size_of;

    #[test]
    fn mixed_record_all_variants_agree() {
        let s = mixed_record::generate(mixed_record::N);
        assert_eq!(s.active[0], 0);
        assert_eq!(s.timestamp[0], 0);
        assert_eq!(s.category[0], 7);
        assert_eq!(s.quantity[0], 17098);
        assert_eq!(s.flags[0], 759);
        assert_eq!(s.amount_ticks[0], 884393);

        let expected = mixed_record::expected_checksum(&s);
        assert_eq!(expected, 2_648_193_599_320);

        assert_eq!(
            mixed_record::sum_natural(&mixed_record::build_natural(&s)),
            expected
        );
        assert_eq!(
            mixed_record::sum_poor_field_order(&mixed_record::build_poor_field_order(&s)),
            expected
        );
        assert_eq!(
            mixed_record::sum_optimized_field_order(&mixed_record::build_optimized_field_order(&s)),
            expected
        );
        assert_eq!(
            mixed_record::sum_cache_line_aligned(&mixed_record::build_cache_line_aligned(&s)),
            expected
        );
        assert_eq!(
            mixed_record::sum_packed_unaligned(&mixed_record::build_packed_unaligned(&s)),
            expected
        );

        // repr(C) layouts must match the Java track's hand-computed byte
        // layouts exactly — the cross-language equivalence contract for
        // this dataset is bytes, not just checksums.
        assert_eq!(size_of::<mixed_record::PoorFieldOrder>(), 40);
        assert_eq!(size_of::<mixed_record::OptimizedFieldOrder>(), 24);
        assert_eq!(size_of::<mixed_record::CacheLineAligned>(), 64);
        assert_eq!(size_of::<mixed_record::PackedUnaligned>(), 24);
    }

    #[test]
    fn header_payload_all_variants_agree() {
        let s = header_payload::generate(header_payload::N);
        assert_eq!(s.msg_type[0], 3);
        assert_eq!(s.msg_flags[0], 182);
        assert_eq!(s.sequence[0], 552163);
        assert_eq!(s.payload[0][0], 736058);

        let expected = header_payload::expected_checksum(&s);
        assert_eq!(expected, 899_586_356_734);

        assert_eq!(
            header_payload::sum_natural(&header_payload::build_natural(&s)),
            expected
        );
        assert_eq!(
            header_payload::sum_poor_field_order(&header_payload::build_poor_field_order(&s)),
            expected
        );
        assert_eq!(
            header_payload::sum_optimized_field_order(
                &header_payload::build_optimized_field_order(&s)
            ),
            expected
        );
        assert_eq!(
            header_payload::sum_cache_line_aligned(&header_payload::build_cache_line_aligned(&s)),
            expected
        );
        assert_eq!(
            header_payload::sum_packed_unaligned(&header_payload::build_packed_unaligned(&s)),
            expected
        );

        assert_eq!(size_of::<header_payload::PoorFieldOrder>(), 80);
        assert_eq!(size_of::<header_payload::OptimizedFieldOrder>(), 72);
        assert_eq!(size_of::<header_payload::CacheLineAligned>(), 128);
        assert_eq!(size_of::<header_payload::PackedUnaligned>(), 70);
    }

    #[test]
    fn producer_consumer_counters_all_variants_count_exactly() {
        use producer_consumer_counters::*;
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::thread;

        let increments = 100_000u64;

        let natural = Natural::new();
        thread::scope(|scope| {
            scope.spawn(|| {
                for _ in 0..increments {
                    natural.producer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
            scope.spawn(|| {
                for _ in 0..increments {
                    natural.consumer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
        });
        assert_eq!(natural.producer_count.load(Ordering::Relaxed), increments);
        assert_eq!(natural.consumer_count.load(Ordering::Relaxed), increments);

        let poor = PoorFieldOrder::new();
        thread::scope(|scope| {
            scope.spawn(|| {
                for _ in 0..increments {
                    poor.producer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
            scope.spawn(|| {
                for _ in 0..increments {
                    poor.consumer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
        });
        assert_eq!(poor.producer_count.load(Ordering::Relaxed), increments);
        assert_eq!(poor.consumer_count.load(Ordering::Relaxed), increments);

        let optimized = OptimizedFieldOrder::new();
        thread::scope(|scope| {
            scope.spawn(|| {
                for _ in 0..increments {
                    optimized.producer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
            scope.spawn(|| {
                for _ in 0..increments {
                    optimized.consumer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
        });
        assert_eq!(optimized.producer_count.load(Ordering::Relaxed), increments);
        assert_eq!(optimized.consumer_count.load(Ordering::Relaxed), increments);

        let aligned = CacheLineAligned::new();
        thread::scope(|scope| {
            scope.spawn(|| {
                for _ in 0..increments {
                    aligned.producer_count.0.fetch_add(1, Ordering::Relaxed);
                }
            });
            scope.spawn(|| {
                for _ in 0..increments {
                    aligned.consumer_count.0.fetch_add(1, Ordering::Relaxed);
                }
            });
        });
        assert_eq!(aligned.producer_count.0.load(Ordering::Relaxed), increments);
        assert_eq!(aligned.consumer_count.0.load(Ordering::Relaxed), increments);

        let packed = PackedUnaligned::new();
        thread::scope(|scope| {
            scope.spawn(|| {
                for _ in 0..increments {
                    packed.producer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
            scope.spawn(|| {
                for _ in 0..increments {
                    packed.consumer_count.fetch_add(1, Ordering::Relaxed);
                }
            });
        });
        assert_eq!(
            packed.producer_count.load(Ordering::Relaxed) as u64,
            increments
        );
        assert_eq!(
            packed.consumer_count.load(Ordering::Relaxed) as u64,
            increments
        );

        assert_eq!(size_of::<CacheLineAlignedCell<AtomicU64>>(), 64);
    }
}
