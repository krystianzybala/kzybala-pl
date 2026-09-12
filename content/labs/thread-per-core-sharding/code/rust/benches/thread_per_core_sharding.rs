//! Criterion benchmark for the `thread-per-core-sharding` lab. Each
//! function runs one mechanism/dataset pair — see benchmark.md for the
//! operation definition and method notes.

use criterion::{criterion_group, criterion_main, Criterion};
use thread_per_core_sharding_lab::{
    mutex_shards, rebalance_simulation, shared_map, single_writer, WorkloadPlan,
};

fn bench_shared_map_uniform(c: &mut Criterion) {
    let plan = WorkloadPlan::uniform();
    c.bench_function("shared_map_uniform", |b| b.iter(|| shared_map::run(&plan)));
}

fn bench_shared_map_skewed(c: &mut Criterion) {
    let plan = WorkloadPlan::skewed();
    c.bench_function("shared_map_skewed", |b| b.iter(|| shared_map::run(&plan)));
}

fn bench_mutex_shards_uniform(c: &mut Criterion) {
    let plan = WorkloadPlan::uniform();
    c.bench_function("mutex_shards_uniform", |b| {
        b.iter(|| mutex_shards::run(&plan))
    });
}

fn bench_mutex_shards_skewed(c: &mut Criterion) {
    let plan = WorkloadPlan::skewed();
    c.bench_function("mutex_shards_skewed", |b| {
        b.iter(|| mutex_shards::run(&plan))
    });
}

fn bench_single_writer_uniform(c: &mut Criterion) {
    let plan = WorkloadPlan::uniform();
    c.bench_function("single_writer_uniform", |b| {
        b.iter(|| single_writer::run(&plan))
    });
}

fn bench_single_writer_skewed(c: &mut Criterion) {
    let plan = WorkloadPlan::skewed();
    c.bench_function("single_writer_skewed", |b| {
        b.iter(|| single_writer::run(&plan))
    });
}

fn bench_rebalance_simulation(c: &mut Criterion) {
    c.bench_function("rebalance_simulation", |b| {
        b.iter(rebalance_simulation::run)
    });
}

criterion_group!(
    benches,
    bench_shared_map_uniform,
    bench_shared_map_skewed,
    bench_mutex_shards_uniform,
    bench_mutex_shards_skewed,
    bench_single_writer_uniform,
    bench_single_writer_skewed,
    bench_rebalance_simulation
);
criterion_main!(benches);
