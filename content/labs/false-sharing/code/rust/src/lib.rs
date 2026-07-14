//! Companion code for the Performance Lab "False sharing" lab
//! (kzybala.pl/lab/false-sharing/). See `rust.md` next to this crate for the
//! full explanation of layout, alignment and ordering choices below.

use std::sync::atomic::{AtomicU64, Ordering};

/// The bug: two independent atomics, adjacent fields of the same struct.
/// `#[repr(C)]` fixes declaration order — Rust's default `repr(Rust)` layout
/// is otherwise unspecified, and the compiler is free to reorder fields.
/// With two adjacent 8-byte `AtomicU64`s only 16 bytes apart, both are
/// comfortably inside one 64-byte line on the common case.
#[repr(C)]
pub struct SharedCounters {
    pub counter_a: AtomicU64,
    pub counter_b: AtomicU64,
}

impl SharedCounters {
    pub fn new() -> Self {
        Self {
            counter_a: AtomicU64::new(0),
            counter_b: AtomicU64::new(0),
        }
    }
}

impl Default for SharedCounters {
    fn default() -> Self {
        Self::new()
    }
}

/// A cache-line-aligned wrapper. `#[repr(align(64))]` is an alignment
/// guarantee the compiler enforces — unlike manual padding fields, this
/// cannot be optimized away, because alignment is part of the type's layout
/// contract. `64` assumes a 64-byte target cache line; state that
/// assumption explicitly wherever you use this (some ARM parts use 128
/// bytes, some embedded targets 32).
#[repr(align(64))]
pub struct CacheLineAligned<T>(pub T);

/// The fix: each counter starts its own cache-line-aligned allocation, so
/// writes to one never invalidate the other's line.
pub struct PaddedCounters {
    pub counter_a: CacheLineAligned<AtomicU64>,
    pub counter_b: CacheLineAligned<AtomicU64>,
}

impl PaddedCounters {
    pub fn new() -> Self {
        Self {
            counter_a: CacheLineAligned(AtomicU64::new(0)),
            counter_b: CacheLineAligned(AtomicU64::new(0)),
        }
    }
}

impl Default for PaddedCounters {
    fn default() -> Self {
        Self::new()
    }
}

/// Per-thread ownership: every thread owns one shard and nobody else ever
/// writes it; a reader reduces over all shards. This removes coherence
/// ping-pong at the root — no cache line is ever written from two cores.
///
/// Each shard is its own `CacheLineAligned` allocation slot inside the
/// `Vec`, so adjacent shards are at least 64 bytes apart (same documented
/// 64-byte-line assumption as `PaddedCounters`).
///
/// Memory order mirrors the Java `ShardedCounters` equivalence contract:
/// the owner does a plain load followed by a `Release` store — no atomic
/// read-modify-write, because a single-writer shard has no write-write race
/// — and the reduction loads with `Acquire`. An exact total is only
/// guaranteed after the owner threads have been joined; a concurrent
/// `total()` is a monotonic lower-bound snapshot.
pub struct ShardedCounters {
    shards: Vec<CacheLineAligned<AtomicU64>>,
}

impl ShardedCounters {
    /// # Panics
    /// Panics if `shard_count == 0` (mirrors the Java constructor contract).
    pub fn new(shard_count: usize) -> Self {
        assert!(
            shard_count >= 1,
            "shard_count must be >= 1, got {shard_count}"
        );
        Self {
            shards: (0..shard_count)
                .map(|_| CacheLineAligned(AtomicU64::new(0)))
                .collect(),
        }
    }

    pub fn shard_count(&self) -> usize {
        self.shards.len()
    }

    /// Owner-only increment — must only ever be called by the shard's owner
    /// thread. Out-of-range `shard` panics (Java throws, same contract).
    pub fn add(&self, shard: usize, delta: u64) {
        let cell = &self.shards[shard].0;
        let current = cell.load(Ordering::Relaxed);
        cell.store(current + delta, Ordering::Release);
    }

    pub fn shard_value(&self, shard: usize) -> u64 {
        self.shards[shard].0.load(Ordering::Acquire)
    }

    /// Reduction over all shards. Exact only after the owners have been joined.
    pub fn total(&self) -> u64 {
        self.shards
            .iter()
            .map(|s| s.0.load(Ordering::Acquire))
            .sum()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::{align_of, size_of};
    use std::sync::Arc;
    use std::thread;

    // Shared fixture ../fixtures/false-sharing-fixtures.json — the Java
    // CounterCorrectnessTest hard-codes exactly the same cases. If you
    // change a value, change all three files in the same commit.

    // Fixture: two-writers-dedicated-counters
    const INCREMENTS_PER_THREAD: u64 = 100_000;
    const EXPECTED_PER_COUNTER: u64 = 100_000;

    // Fixture: four-owners-sharded-reduction
    const SHARD_OWNERS: usize = 4;
    const INCREMENTS_PER_OWNER: u64 = 25_000;
    const EXPECTED_PER_SHARD: u64 = 25_000;
    const EXPECTED_SHARDED_TOTAL: u64 = 100_000;

    fn run_joined(a: impl FnOnce() + Send + 'static, b: impl FnOnce() + Send + 'static) {
        let t1 = thread::spawn(a);
        let t2 = thread::spawn(b);
        t1.join().unwrap();
        t2.join().unwrap();
    }

    #[test]
    fn shared_counters_count_exactly() {
        let c = Arc::new(SharedCounters::new());
        let (c1, c2) = (c.clone(), c.clone());
        run_joined(
            move || {
                for _ in 0..INCREMENTS_PER_THREAD {
                    c1.counter_a.fetch_add(1, Ordering::Relaxed);
                }
            },
            move || {
                for _ in 0..INCREMENTS_PER_THREAD {
                    c2.counter_b.fetch_add(1, Ordering::Relaxed);
                }
            },
        );
        assert_eq!(c.counter_a.load(Ordering::Relaxed), EXPECTED_PER_COUNTER);
        assert_eq!(c.counter_b.load(Ordering::Relaxed), EXPECTED_PER_COUNTER);
    }

    #[test]
    fn padded_counters_count_exactly() {
        let c = Arc::new(PaddedCounters::new());
        let (c1, c2) = (c.clone(), c.clone());
        run_joined(
            move || {
                for _ in 0..INCREMENTS_PER_THREAD {
                    c1.counter_a.0.fetch_add(1, Ordering::Relaxed);
                }
            },
            move || {
                for _ in 0..INCREMENTS_PER_THREAD {
                    c2.counter_b.0.fetch_add(1, Ordering::Relaxed);
                }
            },
        );
        assert_eq!(c.counter_a.0.load(Ordering::Relaxed), EXPECTED_PER_COUNTER);
        assert_eq!(c.counter_b.0.load(Ordering::Relaxed), EXPECTED_PER_COUNTER);
    }

    #[test]
    fn sharded_counters_reduce_exactly_after_join() {
        let c = Arc::new(ShardedCounters::new(SHARD_OWNERS));
        let owners: Vec<_> = (0..SHARD_OWNERS)
            .map(|shard| {
                let c = c.clone();
                thread::spawn(move || {
                    for _ in 0..INCREMENTS_PER_OWNER {
                        c.add(shard, 1);
                    }
                })
            })
            .collect();
        for owner in owners {
            owner.join().unwrap();
        }
        for shard in 0..SHARD_OWNERS {
            assert_eq!(c.shard_value(shard), EXPECTED_PER_SHARD, "shard {shard}");
        }
        assert_eq!(c.total(), EXPECTED_SHARDED_TOTAL);
    }

    #[test]
    #[should_panic(expected = "shard_count must be >= 1")]
    fn sharded_counters_reject_zero_shards() {
        let _ = ShardedCounters::new(0);
    }

    #[test]
    #[should_panic]
    fn sharded_counters_reject_out_of_range_shard() {
        let c = ShardedCounters::new(2);
        c.add(2, 1);
    }

    #[test]
    fn sharded_counters_shards_are_64_byte_aligned() {
        let c = ShardedCounters::new(2);
        assert_eq!(c.shard_count(), 2);
        let a = &c.shards[0] as *const _ as usize;
        let b = &c.shards[1] as *const _ as usize;
        assert_eq!(a % 64, 0);
        assert_eq!(b % 64, 0);
        assert!(b.abs_diff(a) >= 64);
    }

    #[test]
    fn padded_counters_are_64_byte_aligned() {
        assert_eq!(align_of::<CacheLineAligned<AtomicU64>>(), 64);
        assert!(size_of::<PaddedCounters>() >= 128);
    }

    #[test]
    fn counters_increment_independently() {
        let shared = SharedCounters::new();
        shared.counter_a.fetch_add(1, Ordering::Relaxed);
        shared.counter_b.fetch_add(2, Ordering::Relaxed);
        assert_eq!(shared.counter_a.load(Ordering::Relaxed), 1);
        assert_eq!(shared.counter_b.load(Ordering::Relaxed), 2);

        let padded = PaddedCounters::new();
        padded.counter_a.0.fetch_add(1, Ordering::Relaxed);
        padded.counter_b.0.fetch_add(2, Ordering::Relaxed);
        assert_eq!(padded.counter_a.0.load(Ordering::Relaxed), 1);
        assert_eq!(padded.counter_b.0.load(Ordering::Relaxed), 2);
    }
}
