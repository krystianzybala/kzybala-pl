//! Criterion benchmark: shared vs. padded counters under concurrent
//! read/write from two threads. See rust.md for methodology notes and
//! benchmark.md (in the lab content directory) for environment disclosure.

use criterion::{criterion_group, criterion_main, Criterion};
use false_sharing_lab::{PaddedCounters, SharedCounters};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::thread;

const ITERS_PER_ITERATION: u64 = 100_000;

fn run_concurrent_increments<F, G>(iters: u64, op_a: F, op_b: G)
where
    F: Fn() + Send + 'static,
    G: Fn() + Send + 'static,
{
    let t1 = thread::spawn(move || {
        for _ in 0..iters {
            op_a();
        }
    });
    let t2 = thread::spawn(move || {
        for _ in 0..iters {
            op_b();
        }
    });
    t1.join().unwrap();
    t2.join().unwrap();
}

fn bench_shared(c: &mut Criterion) {
    c.bench_function("shared_counters", |b| {
        b.iter(|| {
            let counters = Arc::new(SharedCounters::new());
            let c1 = counters.clone();
            let c2 = counters.clone();
            run_concurrent_increments(
                ITERS_PER_ITERATION,
                move || { c1.counter_a.fetch_add(1, Ordering::Relaxed); },
                move || { c2.counter_b.fetch_add(1, Ordering::Relaxed); },
            );
        });
    });
}

fn bench_padded(c: &mut Criterion) {
    c.bench_function("padded_counters", |b| {
        b.iter(|| {
            let counters = Arc::new(PaddedCounters::new());
            let c1 = counters.clone();
            let c2 = counters.clone();
            run_concurrent_increments(
                ITERS_PER_ITERATION,
                move || { c1.counter_a.0.fetch_add(1, Ordering::Relaxed); },
                move || { c2.counter_b.0.fetch_add(1, Ordering::Relaxed); },
            );
        });
    });
}

criterion_group!(benches, bench_shared, bench_padded);
criterion_main!(benches);
