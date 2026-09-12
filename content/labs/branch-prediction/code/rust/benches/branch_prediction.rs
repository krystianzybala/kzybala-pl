//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `BranchPredictionBenchmark` (Java): the same four variants over the
//! byte-flags dataset, unpinned, for local smoke and profiling. Publication
//! evidence comes exclusively from the `branch_evidence` binary via the
//! native-Linux runner (see benchmark.md).

use branch_prediction_lab::{
    biased_values, filtered_sum_branchless, filtered_sum_branchy, sorted_ascending, BYTE_DOMAIN,
    BYTE_THRESHOLD, N, SEED,
};
use criterion::{black_box, criterion_group, criterion_main, Criterion};

fn bench_variants(c: &mut Criterion) {
    let random5050 = biased_values(50, BYTE_THRESHOLD, BYTE_DOMAIN, SEED, N);
    let biased9010 = biased_values(90, BYTE_THRESHOLD, BYTE_DOMAIN, SEED, N);
    let sorted = sorted_ascending(&random5050);

    let mut group = c.benchmark_group("branch_prediction_byte_flags");

    group.bench_function("biased9010", |b| {
        b.iter(|| filtered_sum_branchy(black_box(&biased9010), BYTE_THRESHOLD))
    });
    group.bench_function("random5050", |b| {
        b.iter(|| filtered_sum_branchy(black_box(&random5050), BYTE_THRESHOLD))
    });
    group.bench_function("sorted", |b| {
        b.iter(|| filtered_sum_branchy(black_box(&sorted), BYTE_THRESHOLD))
    });
    group.bench_function("branchless", |b| {
        b.iter(|| filtered_sum_branchless(black_box(&random5050), BYTE_THRESHOLD))
    });

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
