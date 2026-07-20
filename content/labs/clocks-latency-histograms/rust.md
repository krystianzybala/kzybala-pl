# Clocks, latency histograms and percentiles — Rust track

Crate `clocks_latency_lab`: the same deterministic streams and histogram
conventions as the Java side, over the `hdrhistogram` crate (the Rust port
of HdrHistogram — same bucket math, which is what makes the percentile
fixtures shareable across languages). No `unsafe` anywhere in the crate.

## The pieces

- **`std::time::Instant` calibration** — the `calibration` bin measures
  per-call cost, observed granularity and monotonicity of `Instant::now()`
  on this host (monotonicity violations exit non-zero). `Instant` is the
  monotonic clock; `SystemTime` is wall time and never appears on a
  latency path.
- **Histogram conventions** — `new_histogram()` pins 1 ns–60 s at 3
  significant digits, fixed range (no resize on the hot path);
  `record_correct(value, interval)` is the coordinated-omission
  correction, `merge` the per-thread pattern.
- **Recording cost** — `benches/recording_cost.rs` (Criterion, explicit
  `sample_size`/`measurement_time`/`warm_up_time`, pinned release bench
  profile): bare op, timestamp-every-op (two `Instant::now()` calls +
  record), sampled (every 64th), record-only.
- **Deterministic replays** — the `co_replay` bin mirrors the Java
  harness: `--dataset pause` (naive vs corrected) and `--dataset burst`
  (service vs response time); `fixtureExact` must print `true` or the bin
  exits non-zero.

## Build, correctness gate, run

```bash
cd content/labs/clocks-latency-histograms/code/rust

# correctness gate — sequences, percentiles, CO correction, merge
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# calibrate THIS machine's Instant::now
cargo run --release --bin calibration

# deterministic CO replay (fixtureExact must print true)
cargo run --release --bin co_replay -- --dataset pause
cargo run --release --bin co_replay -- --dataset burst

# recording-cost benchmark (release profile, explicit settings)
cargo bench --bench recording_cost
```

## The documented instrument difference

On the pause dataset, p99.9 of the *naive* histogram falls exactly on the
stall cliff (100 stalls in 100 000 values). The Java implementation
resolves that tie below the cliff (59 999 ns); the Rust port resolves it
on the cliff (5 001 215 ns). Every off-boundary percentile matches
exactly, and the corrected histograms agree to the last count. The
fixtures pin each implementation's own value and the cross-language
comparison excludes that single cell — see benchmark.md's
"percentile-convention trap" for why this is a feature of honest
reporting, not a bug to hide.
