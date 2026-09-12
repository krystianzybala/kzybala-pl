//! Dev/wiring Criterion benchmark — the five lifecycle variants over
//! the `messageBatches` dataset. Publication evidence comes exclusively
//! from the `arena_lifetimes_reuse_evidence` binary and JMH via the
//! native-Linux runner (see benchmark.md).

use arena_lifetimes_reuse_lab::{message_batches, BoundedPool, UnboundedPool};
use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;

fn bench_all(c: &mut Criterion) {
    let source = message_batches::generate(message_batches::N);
    let unbounded = UnboundedPool::new(message_batches::MessageScratch::default);
    let bounded = BoundedPool::new(
        message_batches::POOL_CAPACITY,
        message_batches::MessageScratch::default,
    );

    let mut group = c.benchmark_group("arena_lifetimes_reuse_message_batches");
    group.sample_size(20);

    group.bench_function("allocatePerItem", |b| {
        b.iter(|| message_batches::allocate_per_item(black_box(&source)))
    });
    group.bench_function("batchArena", |b| {
        b.iter(|| message_batches::batch_arena(black_box(&source), message_batches::BATCH_SIZE))
    });
    group.bench_function("threadLocalReuse", |b| {
        b.iter(|| message_batches::thread_local_reuse(black_box(&source)))
    });
    group.bench_function("globalPool", |b| {
        b.iter(|| message_batches::global_pool(black_box(&source), &unbounded))
    });
    group.bench_function("boundedPool", |b| {
        b.iter(|| message_batches::bounded_pool(black_box(&source), &bounded))
    });

    group.finish();
}

criterion_group!(benches, bench_all);
criterion_main!(benches);
