use criterion::{criterion_group, criterion_main, Criterion};
use obs_lab::fixtures::{error_burst, high_cardinality_key, stack_trace_path};
use obs_lab::{run, Variant};

fn bench(c: &mut Criterion) {
    let burst = error_burst();
    let hot_keys = high_cardinality_key();
    let stack_path = stack_trace_path();

    c.bench_function("no_instrumentation", |b| {
        b.iter(|| run(Variant::NoInstrumentation, &burst))
    });
    c.bench_function("disabled_eager_logging", |b| {
        b.iter(|| run(Variant::DisabledEagerLogging, &burst))
    });
    c.bench_function("disabled_lazy_logging", |b| {
        b.iter(|| run(Variant::DisabledLazyLogging, &burst))
    });
    c.bench_function("synchronous_logging", |b| {
        b.iter(|| run(Variant::SynchronousLogging, &burst))
    });
    c.bench_function("async_bounded_logging", |b| {
        b.iter(|| run(Variant::AsyncBoundedLogging, &burst))
    });
    c.bench_function("metrics_labels", |b| {
        b.iter(|| run(Variant::MetricsLabels, &hot_keys))
    });
    c.bench_function("sampled_tracing", |b| {
        b.iter(|| run(Variant::SampledTracing, &stack_path))
    });
}

criterion_group!(benches, bench);
criterion_main!(benches);
