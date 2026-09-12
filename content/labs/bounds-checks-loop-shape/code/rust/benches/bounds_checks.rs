//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `BoundsChecksBenchmark` (Java): the five variants over the primitive-
//! arrays dataset, for local smoke and profiling. Publication evidence
//! comes exclusively from the `bounds_checks_evidence` binary via the
//! native-Linux runner (see benchmark.md).

use bounds_checks_lab::{
    build_backing, primitive_canonical, primitive_irregular_index, primitive_opaque_limit,
    primitive_safe_iterator, random_permutation, unchecked, N, SEED,
};
use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;

fn bench_variants(c: &mut Criterion) {
    let backing = build_backing(N);
    let perm = random_permutation(SEED, N);

    let mut group = c.benchmark_group("bounds_checks_primitive_arrays");

    group.bench_function("canonical", |b| {
        b.iter(|| primitive_canonical(black_box(&backing)))
    });
    group.bench_function("opaqueLimit", |b| {
        b.iter(|| primitive_opaque_limit(black_box(&backing)))
    });
    group.bench_function("irregularIndex", |b| {
        b.iter(|| primitive_irregular_index(black_box(&backing), black_box(&perm)))
    });
    group.bench_function("safeIterator", |b| {
        b.iter(|| primitive_safe_iterator(black_box(&backing)))
    });
    group.bench_function("unchecked", |b| {
        b.iter(|| unsafe { unchecked::sum_unchecked(black_box(&backing), 0..backing.len()) })
    });

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
