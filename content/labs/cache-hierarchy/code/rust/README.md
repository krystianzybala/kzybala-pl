# Cache hierarchy — Rust/Criterion project

Companion code for the [Cache hierarchy](https://kzybala.pl/lab/cache-hierarchy/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

## Test

```sh
cargo test
```

Checks that both chase tables are genuine single N-cycles (no short
sub-cycles) before trusting them as benchmark fixtures.

## Benchmark

```sh
cargo bench
```

Runs four Criterion benchmarks (`sequential_small`, `random_small`,
`sequential_large`, `random_large`) comparing pointer-chase latency over a
16 KB working set (fits L1) against a 128 MB working set (exceeds any
consumer last-level cache). Full HTML report and raw per-sample CSV are
written to `target/criterion/`. Expect several minutes total, dominated by
the two `*_large` benchmarks.

Requires a recent stable Rust toolchain (edition 2021). `rust-toolchain.toml` pins the exact channel used for the disclosed benchmark.md numbers (plab-002); rustup resolves it automatically inside this directory.
