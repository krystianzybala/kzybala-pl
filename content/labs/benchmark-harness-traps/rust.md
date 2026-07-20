# Benchmark harness traps — Rust track

The Rust side uses **Criterion** with fully explicit configuration,
because the third trap this lab teaches is treating default JMH and
default Criterion settings as the same instrument. The kernels live in
`harness_traps_lab` (`src/lib.rs`) and are bit-for-bit equivalent to the
Java kernels (shared fixtures, wrapping arithmetic, no `unsafe` anywhere
in the crate).

## What each Criterion feature is for

- **`black_box`** — the dual-purpose sink/launderer: wrap an *input* to
  make it opaque to LLVM (defeats constant folding), wrap a *result* to
  make it observable (defeats DCE). The `folded_input` variant omits the
  input laundering deliberately; `runtime_input` is the corrected form.
- **Return-value consumption** — Criterion consumes the closure's return
  value, an implicit sink parallel to JMH's; `returned_result` vs
  `consumed_result` shows both sinks side by side.
- **`iter_batched` + `BatchSize`** — the setup-placement control: the
  setup closure runs *outside* the timing, the routine closure inside.
  `setup_inside_timed` rebuilds the parser input in the timed closure;
  `setup_outside` receives it from batched setup.
- **`sample_size`, `measurement_time`, `warm_up_time`** — set explicitly
  (60 samples, 4 s measurement, 2 s warm-up) and disclosed next to every
  number. Rust needs no JIT warm-up, but frequency scaling and caches do.
- **The pinned bench profile** — `[profile.bench] inherits = "release"`
  in Cargo.toml; `cargo bench` cannot accidentally measure a debug build,
  and no lab command overrides the profile.

## What Criterion cannot express

JMH's fork model has no Criterion equivalent: one `cargo bench` process
measures every group. There is no process-isolation contrast on the Rust
side — that asymmetry is a *methodology difference between the
instruments*, stated here rather than papered over, and it is one reason
cross-harness numbers are never presented as one ranking.

## Build, correctness gate, run

```bash
cd content/labs/benchmark-harness-traps/code/rust

# correctness gate — the fixture oracle must pass before any timing
cargo test

# lints and formatting (required before accepting benchmark output)
cargo clippy --all-targets
cargo fmt --check

# development smoke of the full trap/corrected matrix (release profile)
cargo bench --bench harness_traps
```

Raw Criterion output lands under `target/criterion/` (per-group
`estimates.json`, sample data and reports); the evidence pipeline imports
those raw files — numbers are never transcribed by hand.

## Reading the trap variants

- `scalar/folded_input` gives LLVM literal inputs; with the whole
  computation provable, expect aggressive folding — an implausibly small
  cost is the demonstration.
- `scalar/runtime_input` launders both inputs through `black_box`; this
  is the corrected baseline.
- `parser/setup_inside_timed` vs `parser/setup_outside` shows input
  construction cost being attributed to parsing.
- `counter/fresh_state` constructs and steps a fresh counter per sample —
  the corrected form of stateful measurement; the library test
  `stateful_counter_reset_prevents_state_leakage_between_runs` proves the
  leak the trap would cause.
