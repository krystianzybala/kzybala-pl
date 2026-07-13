//! Companion code for the Performance Lab "Cache coherence and MESI" lab
//! (kzybala.pl/lab/mesi/). See `rust.md` next to this crate for the full
//! explanation. Not a benchmark crate — see "Diagnostic methodology" in
//! `theory.md` for the `perf c2c` walkthrough to observe real coherence
//! traffic on Linux yourself.

use std::sync::atomic::{AtomicU64, Ordering};

/// Two threads both call `increment` on the same instance, from different
/// cores — `Relaxed` is sufficient here since only the counter's own
/// atomicity matters, not ordering relative to other memory operations.
pub struct SharedWriter {
    counter: AtomicU64,
}

impl SharedWriter {
    pub fn new() -> Self {
        Self { counter: AtomicU64::new(0) }
    }

    pub fn increment(&self) {
        self.counter.fetch_add(1, Ordering::Relaxed);
    }

    pub fn value(&self) -> u64 {
        self.counter.load(Ordering::Relaxed)
    }
}

impl Default for SharedWriter {
    fn default() -> Self {
        Self::new()
    }
}

/// No atomics, no synchronization: exactly one thread ever touches `total`
/// for the value's whole lifetime, so there is no other core to invalidate
/// this line — the software analogue of an Exclusive/Modified single owner.
pub struct SingleOwner {
    total: u64,
}

impl SingleOwner {
    pub fn new() -> Self {
        Self { total: 0 }
    }

    pub fn add_from(&mut self, iterations: u64) {
        for i in 0..iterations {
            self.total += i;
        }
    }

    pub fn total(&self) -> u64 {
        self.total
    }
}

impl Default for SingleOwner {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn shared_writer_is_correct_under_concurrent_increment() {
        let shared = Arc::new(SharedWriter::new());
        let iterations = 100_000u64;

        let s1 = Arc::clone(&shared);
        let s2 = Arc::clone(&shared);
        let t1 = thread::spawn(move || { for _ in 0..iterations { s1.increment(); } });
        let t2 = thread::spawn(move || { for _ in 0..iterations { s2.increment(); } });
        t1.join().unwrap();
        t2.join().unwrap();

        assert_eq!(shared.value(), iterations * 2);
    }

    #[test]
    fn single_owner_is_correct_on_its_one_owning_thread() {
        let mut owner = SingleOwner::new();
        let iterations = 1_000u64;

        owner.add_from(iterations);

        let expected: u64 = (0..iterations).sum();
        assert_eq!(owner.total(), expected);
    }
}
