use criterion::{criterion_group, criterion_main, Criterion};
use vpe_lab::fixtures::{tasks_for, LONG_CPU_STAGE, SHORT_CPU_STAGE, SIMULATED_SOCKET_WAIT};
use vpe_lab::{
    cpu_bound_pool, fixed_event_loop, high_fanout_os_threads, mixed, thread_per_request,
};

fn bench(c: &mut Criterion) {
    let socket_wait = tasks_for(SIMULATED_SOCKET_WAIT);
    let short_cpu = tasks_for(SHORT_CPU_STAGE);
    let long_cpu = tasks_for(LONG_CPU_STAGE);

    c.bench_function("thread_per_request_socket_wait", |b| {
        b.iter(|| thread_per_request(&socket_wait))
    });
    c.bench_function("high_fanout_os_threads_socket_wait", |b| {
        b.iter(|| high_fanout_os_threads(&socket_wait))
    });
    c.bench_function("fixed_event_loop_socket_wait", |b| {
        b.iter(|| fixed_event_loop(&socket_wait))
    });
    c.bench_function("cpu_bound_pool_short_cpu", |b| {
        b.iter(|| cpu_bound_pool(&short_cpu))
    });
    c.bench_function("fixed_event_loop_long_cpu", |b| {
        b.iter(|| fixed_event_loop(&long_cpu))
    });
    c.bench_function("mixed_socket_wait", |b| b.iter(|| mixed(&socket_wait)));
}

criterion_group!(benches, bench);
criterion_main!(benches);
