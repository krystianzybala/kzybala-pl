//! Criterion counterpart of `HarnessTrapsBenchmark` — the same four
//! kernels, measured correctly and incorrectly. Criterion is configured
//! EXPLICITLY (sample_size, measurement_time, warm_up_time) because the
//! lab's third trap is comparing default JMH and default Criterion settings
//! as if they were identical; the settings used are printed in the output
//! and recorded in benchmark.md.
//!
//! Trap/corrected pairs mirror the Java class:
//! - `folded_input` vs `runtime_input` — a literal the optimizer may fold
//!   vs a `black_box`-laundered runtime value;
//! - `returned_result` vs `consumed_result` — Criterion consumes the
//!   closure's return value vs explicit `black_box` consumption (both are
//!   valid sinks; computing a value nobody observes is NOT benchmarked
//!   because it measures nothing);
//! - `setup_inside_timed` vs `setup_outside` — dataset construction inside
//!   the sample vs prepared via `iter_batched` setup;
//! - process isolation (JMH forks) has no Criterion equivalent — one
//!   `cargo bench` process measures everything, which is itself a
//!   methodology difference benchmark.md discusses.
//!
//! Run with the pinned release bench profile only: `cargo bench` (never a
//! debug build — Cargo.toml pins `[profile.bench] inherits = "release"`).

use criterion::{black_box, criterion_group, criterion_main, BatchSize, Criterion};
use harness_traps_lab::{
    build_parser_input, counter_after, fill_array, mix_scalar, parse_checksum, reduce,
};
use std::time::Duration;

const SCALAR_SEED: u64 = 42;
const SCALAR_ROUNDS: u32 = 1000;
const REDUCTION_SEED: u64 = 42;
const REDUCTION_LENGTH: usize = 4096;
const PARSER_SEED: u64 = 7;
const PARSER_COUNT: usize = 256;
const COUNTER_SEED: u64 = 0;
const COUNTER_STEPS: u32 = 10000;

fn configured() -> Criterion {
    // Explicit, disclosed settings — never compared 1:1 with JMH defaults.
    Criterion::default()
        .sample_size(60)
        .warm_up_time(Duration::from_secs(2))
        .measurement_time(Duration::from_secs(4))
}

fn folded_vs_runtime(c: &mut Criterion) {
    let mut group = c.benchmark_group("scalar");
    // TRAP: literal inputs — the optimizer may precompute arbitrary parts
    // of this whole expression at compile time.
    group.bench_function("folded_input", |b| b.iter(|| mix_scalar(42, 1000)));
    // CORRECTED: identical values, laundered through black_box so they are
    // opaque runtime inputs.
    group.bench_function("runtime_input", |b| {
        b.iter(|| mix_scalar(black_box(SCALAR_SEED), black_box(SCALAR_ROUNDS)))
    });
    group.finish();
}

fn returned_vs_consumed(c: &mut Criterion) {
    let data = fill_array(REDUCTION_SEED, REDUCTION_LENGTH);
    let mut group = c.benchmark_group("reduction");
    // sink #1: Criterion consumes the closure's return value
    group.bench_function("returned_result", |b| b.iter(|| reduce(black_box(&data))));
    // sink #2: explicit black_box consumption of the result
    group.bench_function("consumed_result", |b| {
        b.iter(|| {
            let sum = reduce(black_box(&data));
            black_box(sum);
        })
    });
    group.finish();
}

fn setup_inside_vs_outside(c: &mut Criterion) {
    let input = build_parser_input(PARSER_SEED, PARSER_COUNT);
    let mut group = c.benchmark_group("parser");
    // TRAP: the input is rebuilt inside the timed closure — the reported
    // cost is dominated by setup the experiment never meant to measure.
    group.bench_function("setup_inside_timed", |b| {
        b.iter(|| {
            let rebuilt = build_parser_input(black_box(PARSER_SEED), black_box(PARSER_COUNT));
            parse_checksum(&rebuilt, PARSER_COUNT)
        })
    });
    // CORRECTED: setup runs per-batch OUTSIDE the timing via iter_batched.
    group.bench_function("setup_outside", |b| {
        b.iter_batched(
            || input.clone(),
            |owned| parse_checksum(black_box(&owned), PARSER_COUNT),
            BatchSize::SmallInput,
        )
    });
    group.finish();
}

fn stateful_counter(c: &mut Criterion) {
    let mut group = c.benchmark_group("counter");
    // CORRECTED form: a fresh counter per sample — no state leaks between
    // samples (counter_after constructs and steps its own counter).
    group.bench_function("fresh_state", |b| {
        b.iter(|| counter_after(black_box(COUNTER_SEED), black_box(COUNTER_STEPS)))
    });
    group.finish();
}

criterion_group! {
    name = benches;
    config = configured();
    targets = folded_vs_runtime, returned_vs_consumed, setup_inside_vs_outside, stateful_counter
}
criterion_main!(benches);
