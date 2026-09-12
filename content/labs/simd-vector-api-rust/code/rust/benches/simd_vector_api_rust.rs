//! Dev/wiring Criterion benchmark — the five variants over the
//! `sumMinMax` dataset. Publication evidence comes exclusively from the
//! `simd_vector_api_rust_evidence` binary and JMH via the native-Linux
//! runner (see benchmark.md).

use criterion::{criterion_group, criterion_main, Criterion};
use simd_vector_api_rust_lab::{generate_sum_min_max, sum_min_max, SMALL_TAIL_N, SUM_MIN_MAX_N};
use std::hint::black_box;

fn bench_all(c: &mut Criterion) {
    let value = generate_sum_min_max(SUM_MIN_MAX_N);

    let mut group = c.benchmark_group("simd_vector_api_rust_sum_min_max");
    group.bench_function("scalarBaseline", |b| {
        b.iter(|| sum_min_max::scalar_baseline(black_box(&value)))
    });
    group.bench_function("autoVectorizedCandidate", |b| {
        b.iter(|| sum_min_max::auto_vectorized_candidate(black_box(&value)))
    });
    group.bench_function("explicitSimd", |b| {
        b.iter(|| sum_min_max::explicit_simd(black_box(&value)))
    });
    group.bench_function("misalignedInput", |b| {
        b.iter(|| sum_min_max::explicit_simd(black_box(&value[1..])))
    });
    group.bench_function("smallTailHeavyInput", |b| {
        b.iter(|| sum_min_max::explicit_simd(black_box(&value[..SMALL_TAIL_N])))
    });
    group.finish();
}

criterion_group!(benches, bench_all);
criterion_main!(benches);
