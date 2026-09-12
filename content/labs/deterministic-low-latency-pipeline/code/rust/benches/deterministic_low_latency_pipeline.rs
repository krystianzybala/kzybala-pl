use criterion::{criterion_group, criterion_main, Criterion};
use dllp_lab::fixtures::{medium_events_hot_key_burst, medium_events_uniform_burst};
use dllp_lab::{simulate, PipelineVariant};

fn bench(c: &mut Criterion) {
    let uniform_burst = medium_events_uniform_burst();
    let hot_key_burst = medium_events_hot_key_burst();

    c.bench_function("naive_object_queue", |b| {
        b.iter(|| simulate(PipelineVariant::NaiveObjectQueue, &uniform_burst))
    });
    c.bench_function("optimized", |b| {
        b.iter(|| simulate(PipelineVariant::Optimized, &uniform_burst))
    });
    c.bench_function("overload_profile", |b| {
        b.iter(|| simulate(PipelineVariant::OverloadProfile, &hot_key_burst))
    });
    c.bench_function("fault_restart_profile", |b| {
        b.iter(|| simulate(PipelineVariant::FaultRestartProfile, &hot_key_burst))
    });
}

criterion_group!(benches, bench);
criterion_main!(benches);
