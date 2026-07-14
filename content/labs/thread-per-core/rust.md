# Thread-per-core architecture — Rust

## Owned partition (no synchronization)

```rust
pub struct PartitionedCounter {
    value: u64,
}

impl PartitionedCounter {
    pub fn new() -> Self { Self { value: 0 } }
    pub fn increment(&mut self) -> u64 {
        self.value += 1;
        self.value
    }
    pub fn get(&self) -> u64 { self.value }
}
```

One instance per core/partition. No atomics, no lock — correct only
because exactly one thread ever holds and calls `increment()` on a given
instance.

## Shared counter pool (the baseline being contrasted)

```rust
use std::sync::Mutex;

pub struct SharedCounterPool {
    counters: Mutex<Vec<u64>>,
}

impl SharedCounterPool {
    pub fn new(partitions: usize) -> Self {
        Self { counters: Mutex::new(vec![0; partitions]) }
    }

    pub fn increment(&self, partition: usize) -> u64 {
        let mut guard = self.counters.lock().unwrap();
        guard[partition] += 1;
        guard[partition]
    }
}
```

Every partition's counter lives in one `Vec` behind one `Mutex`. Any
thread may increment any partition index, but the `Mutex` still
serializes every increment across every thread, regardless of which
partition each thread happens to be touching.

## Criterion benchmark

```rust
use thread_per_core_lab::{PartitionedCounter, SharedCounterPool};
use criterion::{criterion_group, criterion_main, Criterion};
use std::sync::Arc;
use std::thread;

const PARTITIONS: usize = 4;
const INCREMENTS_PER_THREAD: u64 = 20_000;

fn run_shared_pool() {
    let pool = Arc::new(SharedCounterPool::new(PARTITIONS));
    let handles: Vec<_> = (0..PARTITIONS).map(|partition| {
        let pool = Arc::clone(&pool);
        thread::spawn(move || { for _ in 0..INCREMENTS_PER_THREAD { pool.increment(partition); } })
    }).collect();
    for h in handles { h.join().unwrap(); }
}

fn run_owned_partitions() {
    let handles: Vec<_> = (0..PARTITIONS).map(|_| {
        thread::spawn(move || {
            let mut counter = PartitionedCounter::new();
            for _ in 0..INCREMENTS_PER_THREAD { counter.increment(); }
        })
    }).collect();
    for h in handles { h.join().unwrap(); }
}
```

Both functions spawn 4 real threads per sample and join them, so — unlike
the SPSC Ring Buffer lab's cross-language benchmark — this comparison is
apples-to-apples: the only difference between the two functions is
whether the counters are shared behind a `Mutex` or owned outright, with
identical thread-spawn overhead on both sides.

The runnable Cargo/Criterion project (with correctness tests) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/thread-per-core/code/rust" rel="noopener"><code>content/labs/thread-per-core/code/rust/</code></a>
in this site's repository.
