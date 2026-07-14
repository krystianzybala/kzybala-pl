# Cache coherence and MESI — Rust project

Companion code for the [Cache coherence and MESI](https://kzybala.pl/lab/mesi/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

This is **not a benchmark crate** — there is no Criterion dependency. It's
two small types (a shared-writer counter, a single-owner counter) plus
correctness tests, meant to be run under Linux `perf c2c` yourself if you
want to observe real coherence traffic. See "Diagnostic methodology" in the
lab's `theory.md` for the `perf c2c` walkthrough.

## Test

```sh
cargo test
```

Confirms both types behave correctly under the concurrency pattern each is
meant to illustrate: `SharedWriter` under two concurrent incrementing
threads, `SingleOwner` on its one owning thread.

Requires a recent stable Rust toolchain (edition 2021). `rust-toolchain.toml` pins the exact channel used for the disclosed benchmark.md numbers (plab-002); rustup resolves it automatically inside this directory.
