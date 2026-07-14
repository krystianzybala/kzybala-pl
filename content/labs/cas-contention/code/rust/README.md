# CAS contention and backoff — Rust project

Companion code for the [CAS contention and backoff](https://kzybala.pl/lab/cas-contention/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

## Test

```sh
cargo test
```

Confirms `CasCounter` is correct under concurrent increment and
`SingleWriterCounter` is correct on its one owning thread.

## Benchmark

```sh
cargo bench
```

Runs five Criterion benchmarks (`cas_increment_1_thread` through
`cas_increment_8_threads`, and `single_writer_increment`) comparing
aggregate CAS throughput at 1/2/4/8 contending threads against a
single-writer counter. Full HTML report and raw per-sample CSV are written
to `target/criterion/`.

Requires a recent stable Rust toolchain (edition 2021). `rust-toolchain.toml` pins the exact channel used for the disclosed benchmark.md numbers (plab-002); rustup resolves it automatically inside this directory.
