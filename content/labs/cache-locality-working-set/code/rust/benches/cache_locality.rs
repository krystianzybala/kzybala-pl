//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `CacheLocalityBenchmark` (Java): the four variants over a fixed
//! 1,000,000-element dataset, for local smoke and profiling. Publication
//! evidence, sized against the detected cache topology, comes exclusively
//! from the `cache_locality_evidence` binary via the native-Linux runner
//! (see benchmark.md).

use cache_locality_lab::{
    random_permutation, sattolo_next, sum_blocked, sum_permuted, sum_pointer_chase, sum_sequential,
};
use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;

const N: usize = 1_000_000;
const SEED: u64 = 42;
const SIDE: usize = 1_000;
const BLOCK: usize = 32;

fn bench_variants(c: &mut Criterion) {
    let perm = random_permutation(SEED, N);
    let next = sattolo_next(SEED, N);

    let mut group = c.benchmark_group("cache_locality_working_set_fixed");

    group.bench_function("sequential", |b| b.iter(|| sum_sequential(black_box(N))));
    group.bench_function("random", |b| b.iter(|| sum_permuted(black_box(&perm))));
    group.bench_function("pointerChase", |b| {
        b.iter(|| sum_pointer_chase(black_box(&next)))
    });
    group.bench_function("blocked", |b| {
        b.iter(|| sum_blocked(black_box(SIDE), black_box(BLOCK)))
    });

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
