//! Criterion counterpart of `RecordingCostBenchmark`: what does observing
//! latency cost the hot path? One fixed-cost operation (a single
//! xorshift64 step) measured bare and under instrumentation. Criterion is
//! configured explicitly (never compared 1:1 with JMH defaults); the
//! pinned release bench profile is mandatory.

use clocks_latency_lab::{new_histogram, xorshift64};
use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::time::{Duration, Instant};

fn configured() -> Criterion {
    Criterion::default()
        .sample_size(60)
        .warm_up_time(Duration::from_secs(2))
        .measurement_time(Duration::from_secs(4))
}

fn recording_cost(c: &mut Criterion) {
    let mut group = c.benchmark_group("recording_cost");

    // the control: the operation alone
    group.bench_function("baseline_op", |b| {
        let mut state: u64 = 42;
        b.iter(|| {
            state = xorshift64(black_box(state));
            state
        })
    });

    // full instrumentation: two Instant::now() calls + histogram record
    group.bench_function("timestamp_every_op", |b| {
        let mut state: u64 = 42;
        let mut h = new_histogram();
        b.iter(|| {
            let t0 = Instant::now();
            state = xorshift64(black_box(state));
            let dt = t0.elapsed().as_nanos() as u64;
            h.record(dt.max(1)).expect("in range");
            state
        })
    });

    // sampling: full instrumentation on every 64th operation
    group.bench_function("sampled_timestamp", |b| {
        let mut state: u64 = 42;
        let mut h = new_histogram();
        let mut tick: u64 = 0;
        b.iter(|| {
            tick += 1;
            if tick & 63 == 0 {
                let t0 = Instant::now();
                state = xorshift64(black_box(state));
                let dt = t0.elapsed().as_nanos() as u64;
                h.record(dt.max(1)).expect("in range");
            } else {
                state = xorshift64(black_box(state));
            }
            state
        })
    });

    // recording cost isolated from clock cost
    group.bench_function("record_only", |b| {
        let mut state: u64 = 42;
        let mut h = new_histogram();
        b.iter(|| {
            state = xorshift64(black_box(state));
            h.record(1000).expect("in range");
            state
        })
    });

    group.finish();
}

criterion_group! {
    name = benches;
    config = configured();
    targets = recording_cost
}
criterion_main!(benches);
