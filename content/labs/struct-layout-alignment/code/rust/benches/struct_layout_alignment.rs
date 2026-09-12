//! Dev/wiring Criterion benchmark — the five layout variants over the
//! `mixedPrimitiveRecord` dataset. Publication evidence comes
//! exclusively from the `struct_layout_alignment_evidence` binary and
//! JMH via the native-Linux runner (see benchmark.md).

use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;
use struct_layout_alignment_lab::mixed_record;

fn bench_all(c: &mut Criterion) {
    let source = mixed_record::generate(mixed_record::N);
    let natural = mixed_record::build_natural(&source);
    let poor = mixed_record::build_poor_field_order(&source);
    let optimized = mixed_record::build_optimized_field_order(&source);
    let aligned = mixed_record::build_cache_line_aligned(&source);
    let packed = mixed_record::build_packed_unaligned(&source);

    let mut group = c.benchmark_group("struct_layout_alignment_sequential_sum_mixed_record");
    group.bench_function("natural", |b| {
        b.iter(|| mixed_record::sum_natural(black_box(&natural)))
    });
    group.bench_function("poorFieldOrder", |b| {
        b.iter(|| mixed_record::sum_poor_field_order(black_box(&poor)))
    });
    group.bench_function("optimizedFieldOrder", |b| {
        b.iter(|| mixed_record::sum_optimized_field_order(black_box(&optimized)))
    });
    group.bench_function("cacheLineAligned", |b| {
        b.iter(|| mixed_record::sum_cache_line_aligned(black_box(&aligned)))
    });
    group.bench_function("packedUnaligned", |b| {
        b.iter(|| mixed_record::sum_packed_unaligned(black_box(&packed)))
    });
    group.finish();
}

criterion_group!(benches, bench_all);
criterion_main!(benches);
