//! Criterion benchmark: aggregate increment throughput at 4 threads under
//! two architectures — a shared, `Mutex`-guarded counter pool versus
//! thread-per-core ownership (each thread increments only its own,
//! unshared counter). See rust.md for methodology notes and benchmark.md
//! (in the lab content directory) for environment disclosure.

use thread_per_core_lab::{PartitionedCounter, SharedCounterPool};
use criterion::{criterion_group, criterion_main, Criterion};
use std::sync::Arc;
use std::thread;

const PARTITIONS: usize = 4;
const INCREMENTS_PER_THREAD: u64 = 20_000;

fn run_shared_pool() {
    let pool = Arc::new(SharedCounterPool::new(PARTITIONS));
    let handles: Vec<_> = (0..PARTITIONS)
        .map(|partition| {
            let pool = Arc::clone(&pool);
            thread::spawn(move || {
                for _ in 0..INCREMENTS_PER_THREAD {
                    pool.increment(partition);
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
}

fn run_owned_partitions() {
    let handles: Vec<_> = (0..PARTITIONS)
        .map(|_| {
            thread::spawn(move || {
                let mut counter = PartitionedCounter::new();
                for _ in 0..INCREMENTS_PER_THREAD {
                    counter.increment();
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
}

fn bench_shared_pool(c: &mut Criterion) {
    c.bench_function("shared_pool_increment_4_threads", |b| b.iter(run_shared_pool));
}

fn bench_owned_partitions(c: &mut Criterion) {
    c.bench_function("owned_partition_increment_4_threads", |b| b.iter(run_owned_partitions));
}

criterion_group!(benches, bench_shared_pool, bench_owned_partitions);
criterion_main!(benches);
