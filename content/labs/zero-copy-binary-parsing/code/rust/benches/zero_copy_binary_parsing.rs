//! Dev/wiring Criterion benchmark — the five decoder variants over the
//! `fixedHeaderPlusVariablePayload` dataset. Publication evidence comes
//! exclusively from the `zero_copy_binary_parsing_evidence` binary and
//! JMH via the native-Linux runner (see benchmark.md).

use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;
use zero_copy_binary_parsing_lab::fixed_header;

fn bench_all(c: &mut Criterion) {
    let (buf, offsets) = fixed_header::encode(fixed_header::N);

    let mut group = c.benchmark_group("zero_copy_binary_parsing_fixed_header");
    group.sample_size(20);

    group.bench_function("copyingDecoder", |b| {
        b.iter(|| {
            fixed_header::copying_decoder(black_box(&buf), black_box(&offsets), fixed_header::N)
        })
    });
    group.bench_function("objectBuildingDecoder", |b| {
        b.iter(|| {
            fixed_header::object_building_decoder(
                black_box(&buf),
                black_box(&offsets),
                fixed_header::N,
            )
        })
    });
    group.bench_function("validatedZeroCopyView", |b| {
        b.iter(|| {
            fixed_header::validated_zero_copy_view(
                black_box(&buf),
                black_box(&offsets),
                fixed_header::N,
            )
        })
    });
    group.bench_function("lazyFieldDecode", |b| {
        b.iter(|| {
            fixed_header::lazy_field_decode(black_box(&buf), black_box(&offsets), fixed_header::N)
        })
    });
    group.bench_function("mutableInPlaceUpdate", |b| {
        let mut mutable = buf.clone();
        b.iter(|| {
            fixed_header::mutable_in_place_update(
                black_box(&mut mutable),
                black_box(&offsets),
                fixed_header::N,
            )
        })
    });

    group.finish();
}

criterion_group!(benches, bench_all);
criterion_main!(benches);
