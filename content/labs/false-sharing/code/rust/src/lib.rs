//! Companion code for the Performance Lab "False sharing" lab
//! (kzybala.pl/lab/false-sharing/). See `rust.md` next to this crate for the
//! full explanation of layout, alignment and ordering choices below.

use std::sync::atomic::AtomicU64;

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
        Self { counter_a: AtomicU64::new(0), counter_b: AtomicU64::new(0) }
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::{align_of, size_of};
    use std::sync::atomic::Ordering;

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
