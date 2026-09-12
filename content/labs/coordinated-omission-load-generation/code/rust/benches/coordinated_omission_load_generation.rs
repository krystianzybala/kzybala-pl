use coload_lab::fixtures::{bounded_server_overload, periodic_ten_ms_stall};
use coload_lab::{simulate, Variant};
use criterion::{criterion_group, criterion_main, Criterion};

fn bench(c: &mut Criterion) {
    let periodic_stall = periodic_ten_ms_stall();
    let overload = bounded_server_overload();

    c.bench_function("closed_loop", |b| {
        b.iter(|| simulate(Variant::ClosedLoop, &periodic_stall))
    });
    c.bench_function("open_loop_fixed_rate", |b| {
        b.iter(|| simulate(Variant::OpenLoopFixedRate, &periodic_stall))
    });
    c.bench_function("poisson_like_arrivals", |b| {
        b.iter(|| simulate(Variant::PoissonLikeArrivals, &periodic_stall))
    });
    c.bench_function("omission_corrected_recording", |b| {
        b.iter(|| simulate(Variant::OmissionCorrectedRecording, &periodic_stall))
    });
    c.bench_function("burst_schedule", |b| {
        b.iter(|| simulate(Variant::BurstSchedule, &periodic_stall))
    });
    c.bench_function("open_loop_under_sustained_overload", |b| {
        b.iter(|| simulate(Variant::OpenLoopFixedRate, &overload))
    });
}

criterion_group!(benches, bench);
criterion_main!(benches);
