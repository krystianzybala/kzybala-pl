//! Dev/wiring Criterion benchmark — the Rust counterpart of
//! `EscapeAnalysisBenchmark` (Java): the five usage-pattern variants over
//! the coordinate dataset, plus `sum_boxed` as a reference point for
//! genuine heap-allocation cost (not part of the cross-language
//! correctness matrix — see rust.md). Publication evidence comes
//! exclusively from the `escape_analysis_evidence` binary via the
//! native-Linux runner (see benchmark.md).

use criterion::{criterion_group, criterion_main, Criterion};
use escape_analysis_lab::{coordinate, run, sum_boxed, AggregateHolder, N};
use std::hint::black_box;

fn bench_variants(c: &mut Criterion) {
    let pair = coordinate(N);
    let mut group = c.benchmark_group("escape_analysis_coordinate");

    for variant in [
        "nonEscaping",
        "returnedObject",
        "storedIntoField",
        "passedToOpaqueCall",
        "identityObserved",
    ] {
        group.bench_function(variant, |b| {
            let mut holder = AggregateHolder::default();
            b.iter(|| {
                run(
                    black_box(variant),
                    black_box(&pair.x),
                    black_box(&pair.y),
                    &mut holder,
                )
            })
        });
    }

    group.bench_function(
        "boxed (reference point, not in the correctness matrix)",
        |b| b.iter(|| sum_boxed(black_box(&pair.x), black_box(&pair.y))),
    );

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
