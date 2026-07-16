//! Criterion benchmark: sequential vs. random pointer-chase latency over a
//! working set that fits in L1 vs. one that exceeds the last-level cache.
//! See rust.md for methodology notes and benchmark.md (in the lab content
//! directory) for environment disclosure.

use cache_hierarchy_lab::{random_cycle, sequential_cycle};
use criterion::{black_box, criterion_group, criterion_main, Criterion};

// 16 KB of u64s — comfortably fits inside L1D on any current desktop/laptop core.
const SMALL_SIZE: usize = 2_048;
// 128 MB of u64s — exceeds any consumer last-level cache.
const LARGE_SIZE: usize = 16_777_216;
const CHASES: usize = 1_000_000;

fn chase(next: &[u64], steps: usize) -> u64 {
    let mut idx: u64 = 0;
    for _ in 0..steps {
        idx = next[idx as usize];
    }
    idx
}

fn bench_sequential_small(c: &mut Criterion) {
    let table = sequential_cycle(SMALL_SIZE);
    c.bench_function("sequential_small", |b| {
        b.iter(|| chase(black_box(&table), CHASES))
    });
}

fn bench_random_small(c: &mut Criterion) {
    let table = random_cycle(SMALL_SIZE, 1);
    c.bench_function("random_small", |b| {
        b.iter(|| chase(black_box(&table), CHASES))
    });
}

fn bench_sequential_large(c: &mut Criterion) {
    let table = sequential_cycle(LARGE_SIZE);
    c.bench_function("sequential_large", |b| {
        b.iter(|| chase(black_box(&table), CHASES))
    });
}

fn bench_random_large(c: &mut Criterion) {
    let table = random_cycle(LARGE_SIZE, 2);
    c.bench_function("random_large", |b| {
        b.iter(|| chase(black_box(&table), CHASES))
    });
}

criterion_group!(
    benches,
    bench_sequential_small,
    bench_random_small,
    bench_sequential_large,
    bench_random_large
);
criterion_main!(benches);
