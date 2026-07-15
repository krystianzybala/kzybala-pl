//! Companion code for the Performance Lab "CAS contention and backoff" lab
//! (kzybala.pl/lab/cas-contention/). See `rust.md` next to this crate for
//! the full explanation.

use std::sync::atomic::{AtomicU64, Ordering};

pub struct CasCounter {
    value: AtomicU64,
}

impl CasCounter {
    pub fn new() -> Self {
        Self {
            value: AtomicU64::new(0),
        }
    }

    /// `fetch_update` already implements the retry loop internally.
    pub fn increment_via_builtin(&self) -> u64 {
        self.value
            .fetch_update(Ordering::SeqCst, Ordering::SeqCst, |v| Some(v + 1))
            .unwrap()
            + 1
    }

    /// The manual form of exactly what [`increment_via_builtin`](Self::increment_via_builtin) does.
    pub fn increment_manually(&self) -> u64 {
        loop {
            let old = self.value.load(Ordering::SeqCst);
            let updated = old + 1;
            if self
                .value
                .compare_exchange(old, updated, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                return updated;
            }
            // else: someone else moved it first — retry
        }
    }

    pub fn get(&self) -> u64 {
        self.value.load(Ordering::SeqCst)
    }
}

impl Default for CasCounter {
    fn default() -> Self {
        Self::new()
    }
}

/// No atomics needed — correct only because exactly one thread ever calls
/// [`increment`](Self::increment). See rust.md "The single-writer alternative".
pub struct SingleWriterCounter {
    value: u64,
}

impl SingleWriterCounter {
    pub fn new() -> Self {
        Self { value: 0 }
    }

    pub fn increment(&mut self) -> u64 {
        self.value += 1;
        self.value
    }

    pub fn get(&self) -> u64 {
        self.value
    }
}

impl Default for SingleWriterCounter {
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
    fn cas_counter_is_correct_under_concurrent_increment() {
        let counter = Arc::new(CasCounter::new());
        let iterations = 50_000u64;
        let handles: Vec<_> = (0..4)
            .map(|_| {
                let c = Arc::clone(&counter);
                thread::spawn(move || {
                    for _ in 0..iterations {
                        c.increment_manually();
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
        assert_eq!(counter.get(), iterations * 4);
    }

    #[test]
    fn single_writer_counter_is_correct_on_its_one_owning_thread() {
        let mut counter = SingleWriterCounter::new();
        for _ in 0..1_000 {
            counter.increment();
        }
        assert_eq!(counter.get(), 1_000);
    }
}
