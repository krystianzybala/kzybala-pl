# Memory ordering in Java and Rust — Rust project

Companion code for the [Memory ordering in Java and Rust](https://kzybala.pl/lab/memory-ordering/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

This is **not a benchmark crate** — reordering effects are timing- and
hardware-dependent (see theory.md "Methodology and limitations"). It's
three small types (relaxed vs. release/acquire publication, a
store-buffering litmus test) plus correctness tests.

## Test

```sh
cargo test -- --nocapture
```

Confirms the release/acquire fix behaves correctly. The store-buffering
test runs 500 iterations and prints how many times it observed the classic
anomaly on your machine/target — it does not fail either way, since not
reproducing it proves nothing (see the lab's investigation task).

Requires a recent stable Rust toolchain (edition 2021). `rust-toolchain.toml` pins the exact channel used for the disclosed benchmark.md numbers (plab-002); rustup resolves it automatically inside this directory.
