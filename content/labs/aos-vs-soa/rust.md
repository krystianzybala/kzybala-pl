# Array of Structures vs Structure of Arrays — Rust track

Crate `aos_vs_soa_lab`: the same deterministic record generation and four
layouts as the Java side. No `unsafe` anywhere in the library crate — the
only `unsafe` in this lab is the worker-affinity syscalls, isolated in the
evidence binary.

## The pieces

- **Generation** — `generate(cold_words, seed, n)` mirrors the Java
  method field-for-field; `xorshift64` operates on native `u64` (unsigned
  by construction, unlike Java's signed `long`).
- **`AosHeapLayout`** (variant 1) — `Vec<AosHeapRecord>` where each
  record owns its own heap-allocated `Vec<i64>` cold payload: the closest
  Rust analogue to Java's per-object indirection, without an object
  header (Rust has none) but with the same extra-allocation-per-record
  cost.
- **`AosPackedLayout<const COLD: usize>`** (variant 2) — a genuine
  `#[repr(C)]` struct, `AosPackedRecord<const COLD: usize> { hot_a: i64,
  hot_b: i64, cold: [i64; COLD] }`, stored in one `Vec<AosPackedRecord<COLD>>`.
  `COLD` is a **compile-time** constant (2, 4 or 8 — this lab's three
  dataset cold-word counts); this is what makes it a real, inspectable
  struct type rather than a runtime byte layout, and it is the direct Rust
  counterpart of the Java side's `MemorySegment`-derived stride.
- **`SoaLayout`** (variant 3) / **`HybridLayout`** (variant 4) — parallel
  `Vec<i64>` fields, matching the Java arrays exactly; hybrid interleaves
  the two hot fields into one vector.
- **`benches/aos_vs_soa.rs`** (Criterion, dev/wiring) — the four layouts
  over the market-quotes dataset, pinned release profile, `black_box`
  around every layout reference.
- **`src/bin/aos_soa_evidence.rs`** (publication) — the Rust counterpart
  of `AosVsSoaLinuxEvidenceBenchmark`: since `AosPackedLayout<COLD>` needs
  `COLD` at compile time, the runtime `--dataset` argument is matched
  **once** in `main()` to select the correct monomorphized `run::<COLD>`
  instantiation (2, 4 or 8) — everything past that match is ordinary,
  non-generic-dispatch code.

## Build, correctness gate, run

```bash
cd content/labs/aos-vs-soa/code/rust

# correctness gate — generation + all four layouts agree, every dataset
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev smoke — Criterion, market-quotes dataset, all four layouts
cargo bench --bench aos_vs_soa

# one cell of the publication matrix, unpinned dev run
cargo run --release --bin aos_soa_evidence -- \
  --variant hybrid --dataset positions --seconds 2 --warmup-seconds 1
```

## Cross-language parity notes

- Const generics (`AosPackedRecord<const COLD: usize>`) give Rust a
  genuine compile-time-sized `#[repr(C)]` struct per dataset; Java's FFM
  `MemoryLayout` is built at **runtime** from the same `coldWords` value
  instead — both describe the identical byte layout (verified by the
  shared `record_stride_bytes` fixture value per dataset), but Rust's
  version is a real type the compiler checks, while Java's is data the
  program constructs. This is a genuine, not incidental, language
  difference in how "the same struct" is expressed, and it is called out
  rather than smoothed over.
- `AosHeapLayout` is *not* Java's AoS-heap variant translated line-by-line
  — Rust has no object header, so the per-record cost here is purely the
  extra `Vec<i64>` allocation and its pointer, not header overhead. Any
  cross-language comparison of this specific variant's numbers would be
  comparing different mechanisms with the same name, not a language speed
  result (the non-goal this lab explicitly avoids).
- No cross-harness ranking is published between JMH and Criterion; the
  native-Linux evidence runner treats Java as the measured side for this
  lab's publication numbers (benchmark.md), and Rust's Criterion/
  `aos_soa_evidence` runs are a separately disclosed instrument.
