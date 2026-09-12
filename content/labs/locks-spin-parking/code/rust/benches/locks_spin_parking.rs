//! Criterion benchmark for the `locks-spin-parking` lab. Each function
//! runs one wait-strategy variant — see benchmark.md for the operation
//! definition and method notes.

use criterion::{criterion_group, criterion_main, Criterion};
use locks_spin_parking_lab::{cas_loop, fixtures, mutex_kernel, spin_then_park};

fn bench_uncontended(c: &mut Criterion) {
    c.bench_function("uncontended_mutex", |b| b.iter(mutex_kernel::uncontended));
}

fn bench_short_contended(c: &mut Criterion) {
    c.bench_function("short_contended", |b| {
        b.iter(|| mutex_kernel::short_contended(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER))
    });
}

fn bench_long_critical_section(c: &mut Criterion) {
    c.bench_function("long_critical_section", |b| {
        b.iter(|| {
            mutex_kernel::long_critical_section(
                fixtures::WORKER_COUNT,
                fixtures::OPS_PER_WORKER,
                fixtures::LONG_CS_BUSY_ITERATIONS,
            )
        })
    });
}

fn bench_cas_loop(c: &mut Criterion) {
    c.bench_function("cas_loop", |b| {
        b.iter(|| cas_loop::run(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER))
    });
}

fn bench_spin_then_park(c: &mut Criterion) {
    c.bench_function("spin_then_park", |b| {
        b.iter(|| {
            spin_then_park::run(
                fixtures::WORKER_COUNT,
                fixtures::OPS_PER_WORKER,
                fixtures::SPIN_LIMIT,
            )
        })
    });
}

criterion_group!(
    benches,
    bench_uncontended,
    bench_short_contended,
    bench_long_critical_section,
    bench_cas_loop,
    bench_spin_then_park
);
criterion_main!(benches);
