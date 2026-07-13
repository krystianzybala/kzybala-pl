# False sharing — Rust/Criterion project

Companion code for the [False sharing](https://kzybala.pl/lab/false-sharing/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

## Test

```sh
cargo test
```

Checks that `PaddedCounters` fields are actually 64-byte aligned and that
both counter layouts increment independently and correctly.

## Benchmark

```sh
cargo bench
```

Runs the Criterion benchmark comparing `SharedCounters` (adjacent atomics,
one cache line) against `PaddedCounters` (`#[repr(align(64))]`, separate
lines) under concurrent writes from two threads. Full HTML report and raw
per-sample CSV are written to `target/criterion/`.

Requires a recent stable Rust toolchain (edition 2021).
