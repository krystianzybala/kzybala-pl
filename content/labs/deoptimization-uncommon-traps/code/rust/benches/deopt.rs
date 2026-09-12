//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `DeoptBenchmark` (Java): aggregate throughput of the five variants
//! over the strategy-dispatch dataset. The real evidence is the
//! `deopt_timeline` binary (a per-call/per-batch latency timeline, not
//! an aggregate) — see rust.md and benchmark.md.

use criterion::{criterion_group, criterion_main, Criterion};
use deopt_lab::{run, strategy_dispatch, N};
use std::hint::black_box;

fn bench_variants(c: &mut Criterion) {
    let inputs = strategy_dispatch(N);
    let mut group = c.benchmark_group("deopt_strategy_dispatch");

    for variant in [
        "stableTypeProfile",
        "profileShiftAfterWarmup",
        "rareExceptionPath",
        "lateSubtypeLoading",
        "nullabilityShift",
    ] {
        group.bench_function(variant, |b| {
            b.iter(|| run(black_box(variant), black_box(&inputs)))
        });
    }

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
