//! Companion code for the Performance Lab "Thread-per-Core Architecture"
//! lab (kzybala.pl/lab/thread-per-core/). See `rust.md` next to this crate
//! for the full explanation.

use std::sync::Mutex;

/// One instance per core/partition. No synchronization at all — correct
/// only because exactly one thread (the one that owns this partition)
/// ever calls [`increment`](Self::increment). Contrast with
/// [`SharedCounterPool`], which protects the same kind of state with a
/// lock because it is shared across every worker thread.
pub struct PartitionedCounter {
    value: u64,
}

impl PartitionedCounter {
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

impl Default for PartitionedCounter {
    fn default() -> Self {
        Self::new()
    }
}

/// The shared-worker-pool baseline: every partition's counter lives in
/// one `Vec`, guarded by one [`Mutex`]. Any worker thread may increment
/// any partition, but only one increment — on any partition — can
/// proceed at a time, because they all serialize on the same lock.
pub struct SharedCounterPool {
    counters: Mutex<Vec<u64>>,
}

impl SharedCounterPool {
    pub fn new(partitions: usize) -> Self {
        Self {
            counters: Mutex::new(vec![0; partitions]),
        }
    }

    pub fn increment(&self, partition: usize) -> u64 {
        let mut guard = self.counters.lock().unwrap();
        guard[partition] += 1;
        guard[partition]
    }

    pub fn get(&self, partition: usize) -> u64 {
        self.counters.lock().unwrap()[partition]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn partitioned_counter_is_correct_on_its_one_owning_thread() {
        let mut counter = PartitionedCounter::new();
        for _ in 0..10_000 {
            counter.increment();
        }
        assert_eq!(counter.get(), 10_000);
    }

    #[test]
    fn shared_counter_pool_is_correct_under_concurrent_increment_across_partitions() {
        let partitions = 4;
        let increments_per_thread = 20_000u64;
        let pool = Arc::new(SharedCounterPool::new(partitions));

        let handles: Vec<_> = (0..partitions)
            .map(|partition| {
                let pool = Arc::clone(&pool);
                thread::spawn(move || {
                    for _ in 0..increments_per_thread {
                        pool.increment(partition);
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }

        for partition in 0..partitions {
            assert_eq!(
                pool.get(partition),
                increments_per_thread,
                "partition {partition} should only reflect its own thread's increments"
            );
        }
    }

    #[test]
    fn owned_partitions_each_thread_only_ever_sees_its_own_counter() {
        let core_count = 4;
        let increments_per_thread = 20_000u64;

        let handles: Vec<_> = (0..core_count)
            .map(|_| {
                thread::spawn(move || {
                    let mut counter = PartitionedCounter::new();
                    for _ in 0..increments_per_thread {
                        counter.increment();
                    }
                    counter.get()
                })
            })
            .collect();

        for h in handles {
            assert_eq!(h.join().unwrap(), increments_per_thread);
        }
    }
}
