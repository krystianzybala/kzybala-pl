//! Dev/wiring Criterion benchmark — the five allocation-pattern
//! variants over the `objectGraphChurn` dataset. Publication evidence
//! (allocation latency, reclamation timing) comes exclusively from the
//! `gc_tail_harness` binary via the native-Linux runner (see
//! benchmark.md); this bench only exercises the same code paths for
//! local/CI wiring sanity, matching the established Criterion-vs-
//! evidence-binary split from other labs in this track.

use criterion::{criterion_group, criterion_main, Criterion};
use gc_tail_lab::{extra_size_for, run, values_for, N};
use std::hint::black_box;

fn bench_variants(c: &mut Criterion) {
    let values = values_for("objectGraphChurn", N);
    let extra_size = extra_size_for("objectGraphChurn");
    let mut group = c.benchmark_group("gc_tail_object_graph_churn");
    group.sample_size(20);

    for variant in [
        "lowAllocationReuse",
        "steadyHighAllocation",
        "burstyAllocation",
        "growingLiveSet",
        "collectorMatrix",
    ] {
        group.bench_function(variant, |b| {
            b.iter(|| {
                run(
                    black_box(variant),
                    black_box(&values),
                    black_box(extra_size),
                )
            })
        });
    }

    group.finish();
}

criterion_group!(benches, bench_variants);
criterion_main!(benches);
