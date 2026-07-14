# False sharing — Rust/Criterion project

Companion code for the [False sharing](https://kzybala.pl/lab/false-sharing/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

## Test

```sh
cargo test
```

Checks that `PaddedCounters` fields and `ShardedCounters` shards are
actually 64-byte aligned, and that all three layouts (shared, padded,
sharded) produce exact counts under concurrent writers — asserted against
the shared fixture `../fixtures/false-sharing-fixtures.json` (the Java
tests assert the same cases).

## Benchmark

```sh
cargo bench
```

Runs the Criterion benchmark comparing `SharedCounters` (adjacent atomics,
one cache line), `PaddedCounters` (`#[repr(align(64))]`, separate lines)
and `ShardedCounters` (per-thread ownership + reduction) under concurrent
writes from two threads. Full HTML report and raw per-sample CSV are
written to `target/criterion/`.

Requires a recent stable Rust toolchain (edition 2021). `rust-toolchain.toml` pins the exact channel used for the disclosed benchmark.md numbers (plab-002); rustup resolves it automatically inside this directory.
