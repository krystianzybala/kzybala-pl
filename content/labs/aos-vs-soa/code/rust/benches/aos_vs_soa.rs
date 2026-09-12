//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `AosVsSoaBenchmark` (Java): the four layouts over the market-quotes
//! dataset, for local smoke and profiling. Publication evidence comes
//! exclusively from the `aos_soa_evidence` binary via the native-Linux
//! runner (see benchmark.md).

use aos_vs_soa_lab::{generate, AosHeapLayout, AosPackedLayout, HybridLayout, SoaLayout, N, SEED};
use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;

fn bench_layouts(c: &mut Criterion) {
    let data = generate(4, SEED, N);
    let heap = AosHeapLayout::of(&data);
    let packed = AosPackedLayout::<4>::of(&data);
    let soa = SoaLayout::of(&data, 4);
    let hybrid = HybridLayout::of(&data, 4);

    let mut group = c.benchmark_group("aos_vs_soa_market_quotes");

    group.bench_function("aosHeap", |b| b.iter(|| black_box(&heap).sum_hot()));
    group.bench_function("aosPacked", |b| b.iter(|| black_box(&packed).sum_hot()));
    group.bench_function("soa", |b| b.iter(|| black_box(&soa).sum_hot()));
    group.bench_function("hybrid", |b| b.iter(|| black_box(&hybrid).sum_hot()));

    group.finish();
}

criterion_group!(benches, bench_layouts);
criterion_main!(benches);
