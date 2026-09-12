//! Dev/wiring Criterion benchmark — the four representation variants
//! over the `ordersQuotes` dataset, matching the construct/sumHot split
//! from the Java track's `AllocationObjectLayoutBenchmark`. Publication
//! evidence comes exclusively from the `allocation_object_layout_evidence`
//! binary via the native-Linux runner (see benchmark.md).

use allocation_object_layout_lab::{
    boxed_object_graph, flat_primitive_arrays, generate_orders, packed_off_heap_struct,
    reused_mutable_holder, OrdersSource, ORDERS_N,
};
use criterion::{criterion_group, criterion_main, Criterion};
use std::hint::black_box;

fn bench_construct(c: &mut Criterion, source: &OrdersSource) {
    let mut group = c.benchmark_group("allocation_object_layout_construct_orders");

    group.bench_function("boxedObjectGraph", |b| {
        b.iter(|| {
            boxed_object_graph::sum_orders(&boxed_object_graph::build_orders(black_box(source)))
        })
    });
    group.bench_function("flatPrimitiveArrays", |b| {
        b.iter(|| {
            flat_primitive_arrays::sum_orders(&flat_primitive_arrays::build_orders(black_box(
                source,
            )))
        })
    });
    group.bench_function("packedOffHeapStruct", |b| {
        b.iter(|| {
            packed_off_heap_struct::sum_orders(&packed_off_heap_struct::build_orders(black_box(
                source,
            )))
        })
    });
    group.bench_function("reusedMutableHolder", |b| {
        b.iter(|| reused_mutable_holder::stream_orders(black_box(source)))
    });

    group.finish();
}

fn bench_sum_hot(c: &mut Criterion, source: &OrdersSource) {
    let boxed = boxed_object_graph::build_orders(source);
    let flat = flat_primitive_arrays::build_orders(source);
    let packed = packed_off_heap_struct::build_orders(source);

    let mut group = c.benchmark_group("allocation_object_layout_sum_hot_orders");

    group.bench_function("boxedObjectGraph", |b| {
        b.iter(|| boxed_object_graph::sum_orders(black_box(&boxed)))
    });
    group.bench_function("flatPrimitiveArrays", |b| {
        b.iter(|| flat_primitive_arrays::sum_orders(black_box(&flat)))
    });
    group.bench_function("packedOffHeapStruct", |b| {
        b.iter(|| packed_off_heap_struct::sum_orders(black_box(&packed)))
    });
    group.bench_function("reusedMutableHolder", |b| {
        b.iter(|| reused_mutable_holder::stream_orders(black_box(source)))
    });

    group.finish();
}

fn bench_all(c: &mut Criterion) {
    let source = generate_orders(ORDERS_N);
    bench_construct(c, &source);
    bench_sum_hot(c, &source);
}

criterion_group!(benches, bench_all);
criterion_main!(benches);
