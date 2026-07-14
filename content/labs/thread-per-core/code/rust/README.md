# Thread-per-core architecture — Rust project

Companion code for the [Thread-per-Core Architecture](https://kzybala.pl/lab/thread-per-core/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

## Test

```sh
cargo test
```

Confirms `PartitionedCounter` is correct on its one owning thread, and
`SharedCounterPool` is correct under concurrent increments across
partitions from multiple threads.

## Benchmark

```sh
cargo bench
```

Runs `shared_pool_increment_4_threads` (4 threads contending for one
`Mutex`, each incrementing its own partition index) and
`owned_partition_increment_4_threads` (4 threads, each with its own
unshared counter), 20,000 increments per thread per sample. Full HTML
report and raw per-sample CSV are written to `target/criterion/`.

Requires a recent stable Rust toolchain (edition 2021). `rust-toolchain.toml` pins the exact channel used for the disclosed benchmark.md numbers (plab-002); rustup resolves it automatically inside this directory.
