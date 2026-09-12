//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `InliningBenchmark` (Java): the five variants over the pricing-
//! functions dataset, for local smoke and profiling. Publication
//! evidence comes exclusively from the `inlining_evidence` binary via
//! the native-Linux runner (see benchmark.md).

use criterion::{criterion_group, criterion_main, Criterion};
use inlining_lab::{pricing_functions, run, N};
use std::hint::black_box;

fn bench_variants(c: &mut Criterion) {
    let inputs = pricing_functions(N);
    let mut group = c.benchmark_group("inlining_pricing_functions");

    for variant in [
        "monomorphic",
        "bimorphic",
        "megamorphic",
        "switchDispatch",
        "oversizedCallee",
    ] {
        group.bench_function(variant, |b| {
            b.iter(|| run(black_box(variant), black_box(&inputs)))
        });
    }

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
