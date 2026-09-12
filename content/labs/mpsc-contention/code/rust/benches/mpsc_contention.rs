//! Criterion benchmark for the `mpsc-contention` lab. Each group runs
//! one fan-in variant across the correctness-suite producer counts — see
//! benchmark.md for the operation definition and method notes.

use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion};
use mpsc_contention_lab::{
    batched_claim, fixtures, library_queue, mutex_queue, per_producer_fan_in, shared_mpsc,
};

fn bench_variant(c: &mut Criterion, name: &str, f: impl Fn(usize)) {
    let mut group = c.benchmark_group(name);
    group.sample_size(20);
    for &producers in fixtures::PRODUCER_COUNTS.iter() {
        group.bench_with_input(
            BenchmarkId::new("producers", producers),
            &producers,
            |b, &p| {
                b.iter(|| f(p));
            },
        );
    }
    group.finish();
}

fn bench_shared_mpsc(c: &mut Criterion) {
    bench_variant(c, "shared_mpsc", |p| {
        shared_mpsc::run(p, fixtures::ITEMS_PER_PRODUCER, fixtures::CAPACITY);
    });
}

fn bench_batched_claim(c: &mut Criterion) {
    bench_variant(c, "batched_claim", |p| {
        batched_claim::run(
            p,
            fixtures::ITEMS_PER_PRODUCER,
            fixtures::CAPACITY,
            fixtures::BATCH_SIZE,
        );
    });
}

fn bench_per_producer_fan_in(c: &mut Criterion) {
    bench_variant(c, "per_producer_fan_in", |p| {
        per_producer_fan_in::run(p, fixtures::ITEMS_PER_PRODUCER, fixtures::CAPACITY);
    });
}

fn bench_mutex_queue(c: &mut Criterion) {
    bench_variant(c, "mutex_queue", |p| {
        mutex_queue::run(p, fixtures::ITEMS_PER_PRODUCER, fixtures::CAPACITY);
    });
}

fn bench_library_queue(c: &mut Criterion) {
    bench_variant(c, "library_queue", |p| {
        library_queue::run(p, fixtures::ITEMS_PER_PRODUCER);
    });
}

criterion_group!(
    benches,
    bench_shared_mpsc,
    bench_batched_claim,
    bench_per_producer_fan_in,
    bench_mutex_queue,
    bench_library_queue
);
criterion_main!(benches);
