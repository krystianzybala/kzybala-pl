use bp_lab::fixtures::{hot_key_skew, sustained_overload};
use bp_lab::{simulate, Policy};
use criterion::{criterion_group, criterion_main, Criterion};

fn bench(c: &mut Criterion) {
    let overload = sustained_overload();
    let hot_key = hot_key_skew();

    c.bench_function("unbounded", |b| {
        b.iter(|| simulate(Policy::Unbounded, &overload))
    });
    c.bench_function("bounded_reject", |b| {
        b.iter(|| simulate(Policy::BoundedReject, &overload))
    });
    c.bench_function("bounded_block", |b| {
        b.iter(|| simulate(Policy::BoundedBlock, &overload))
    });
    c.bench_function("drop_oldest", |b| {
        b.iter(|| simulate(Policy::DropOldest, &overload))
    });
    c.bench_function("coalesce_by_key", |b| {
        b.iter(|| simulate(Policy::CoalesceByKey, &hot_key))
    });
    c.bench_function("load_shedding", |b| {
        b.iter(|| simulate(Policy::LoadShedding, &overload))
    });
}

criterion_group!(benches, bench);
criterion_main!(benches);
