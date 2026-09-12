# Cache locality and working-set size — Rust track

Crate `cache_locality_lab`: the same deterministic traversal-order
generation and topology detection as the Java side. No `unsafe` anywhere
in the library crate — the only `unsafe` in this lab is the worker-
affinity syscalls, isolated in the evidence binary.

## The pieces

- **Traversal orders** — `random_permutation` (Fisher-Yates) and
  `sattolo_next` (a single N-cycle) mirror the Java methods field-for-
  field; `xorshift64` operates on native `u64`.
- **`sum_blocked`** — the same tiled column-major traversal as the Java
  side, over a `side × side` conceptual matrix.
- **Topology detection** (`src/bin/cache_locality_evidence.rs`) — reads
  `/sys/devices/system/cpu/cpu0/cache`, the same convention as
  content/labs/cache-hierarchy's Rust evidence binary, with the same
  documented fallback off Linux.
- **`benches/cache_locality.rs`** (Criterion, dev/wiring) — the four
  variants over a fixed 1,000,000-element dataset, pinned release
  profile, `black_box` around every input.
- **`src/bin/cache_locality_evidence.rs`** (publication) — the Rust
  counterpart of `CacheLocalityLinuxEvidenceBenchmark`: single pinned
  worker, working sets sized against the DETECTED topology, the
  `n·(n−1)/2` correctness oracle before any timing, one JSON document on
  stdout, non-zero exit on any correctness/placement violation.

## Build, correctness gate, run

```bash
cd content/labs/cache-locality-working-set/code/rust

# correctness gate — every traversal order sums to n*(n-1)/2
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev smoke — Criterion, fixed 1,000,000-element dataset
cargo bench --bench cache_locality

# one cell of the publication matrix, unpinned dev run
cargo run --release --bin cache_locality_evidence -- \
  --variant pointerChase --working-set llc --seconds 2 --warmup-seconds 1
```

## Cross-language parity notes

- Both languages derive working-set element counts from the identical
  topology-detection convention (same sysfs paths, same fallback sizes,
  same per-tier byte formulas) — a `workingSet` name means the same byte
  footprint in both languages on the same host, which is what makes the
  cross-language correctness fixture ("every variant sums to
  `n·(n−1)/2`") meaningful rather than coincidental.
- No cross-harness ranking is published between JMH and Criterion; the
  native-Linux evidence runner treats Java as the measured side for this
  lab's publication numbers (benchmark.md), and Rust's Criterion/
  `cache_locality_evidence` runs are a separately disclosed instrument.
