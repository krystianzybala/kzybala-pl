//! Dev/wiring Criterion benchmark — the five storage variants over the
//! `fixedRecords` dataset, matching the sequentialSum/randomAccess split
//! from the Java track's `FfmMemorySegmentsBenchmark`. Publication
//! evidence comes exclusively from the `ffm_memory_segments_evidence`
//! binary via the native-Linux runner (see benchmark.md).

use criterion::{criterion_group, criterion_main, Criterion};
use ffm_memory_segments_lab::{fixed_records, random_indices, RecordStorage};
use std::hint::black_box;

const RANDOM_ACCESS_K: usize = 10_000;

fn bench_all(c: &mut Criterion) {
    let source = fixed_records::generate(fixed_records::N);
    let indices = random_indices(fixed_records::N, RANDOM_ACCESS_K, 950);

    let heap = fixed_records::heap_primitive_array(&source);
    let confined = fixed_records::confined_segment(&source);
    let shared = fixed_records::shared_segment(&source);
    let sliced = fixed_records::sliced_view(&source);
    let copied = fixed_records::copied_boundary_crossing(&source);

    let mut seq = c.benchmark_group("ffm_memory_segments_sequential_sum_fixed_records");
    seq.bench_function("heapPrimitiveArray", |b| b.iter(|| heap.sequential_sum()));
    seq.bench_function("confinedSegment", |b| b.iter(|| confined.sequential_sum()));
    seq.bench_function("sharedSegment", |b| b.iter(|| shared.sequential_sum()));
    seq.bench_function("slicedView", |b| b.iter(|| sliced.sequential_sum()));
    seq.bench_function("copiedBoundaryCrossing", |b| {
        b.iter(|| copied.sequential_sum())
    });
    seq.finish();

    let mut rnd = c.benchmark_group("ffm_memory_segments_random_access_fixed_records");
    rnd.bench_function("heapPrimitiveArray", |b| {
        b.iter(|| heap.random_access(black_box(&indices)))
    });
    rnd.bench_function("confinedSegment", |b| {
        b.iter(|| confined.random_access(black_box(&indices)))
    });
    rnd.bench_function("sharedSegment", |b| {
        b.iter(|| shared.random_access(black_box(&indices)))
    });
    rnd.bench_function("slicedView", |b| {
        b.iter(|| sliced.random_access(black_box(&indices)))
    });
    rnd.bench_function("copiedBoundaryCrossing", |b| {
        b.iter(|| copied.random_access(black_box(&indices)))
    });
    rnd.finish();
}

criterion_group!(benches, bench_all);
criterion_main!(benches);
