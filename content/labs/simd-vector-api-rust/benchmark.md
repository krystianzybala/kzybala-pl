# SIMD: Java Vector API and Rust — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`). This lab's vector-width assumptions
specifically must be re-confirmed on the publication host — see
Limitations.

## What is deterministic vs what is measured

- **Deterministic** — every variant's result over a given dataset's
  identical value stream: vectorization strategy must never change the
  result (`code/fixtures/simd-vector-api-rust-fixtures.json`).
- **Measured** — ns/element, cycles/element, vector instructions,
  bandwidth and speedup-by-size, for each of the 20 (variant × dataset)
  cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One "operation" | Oracle |
|---|---|---|
| `run` | one full pass over the declared dataset, computing the kernel's result | the variant's result matches the fixture-pinned value for the declared dataset (and, for `misalignedInput`/`smallTailHeavyInput`, for the SAME input shape the variant actually consumes — a real bug this lab's own Java setup code had to fix, see java.md) |

Java is the measured side for this lab's publication numbers; the Rust
track builds an equivalent evidence binary and runs it separately
(rust.md) — no cross-harness ranking, and no cross-language ranking, is
published.

## Required metrics

Ns/element (`run`'s own reported ns/op, divided by element count),
cycles/element and vector instructions (`perf stat`'s `cycles`/
`instructions` counters wrapping the pinned worker process,
`scripts/performance-lab/labs/simd-vector-api-rust.conf`,
`LAB_PERF_EVENTS`), bandwidth (derived from element count × element
size ÷ elapsed time for the memory-bound kernels), speedup-by-size
(comparing `explicitSimd`'s full-dataset result against
`smallTailHeavyInput`'s N=17 result, both against their own
`scalarBaseline` reference — never comparing across kernels or
languages).

## Profiler evidence

`perf stat`/`perfasm` are the primary evidence for vector-instruction
counts and cycles/element; `cargo asm` supplements the Rust track where
the host supports it, confirming the actual NEON/SSE2 instructions a
given `explicit_simd` function compiles to. Compiler optimization
reports (`-XX:+PrintCompilation`/`-Xlog:class+load` for
`autoVectorizedCandidate`'s actual JIT treatment; `rustc`'s own
`--emit=llvm-ir`/`-C opt-level` reports for the Rust auto-vec kernels)
are recorded as unavailable rather than substituted where the host does
not support capturing them.

## A real finding from development wiring (dev-only, never published)

Running this lab's four kernels on this repository's development
machine showed real, but DIFFERENT-magnitude, speedups from explicit
SIMD in Java: `byteClassification` ≈51.5×, `sumMinMax` ≈4.0×,
`thresholdFilter` ≈3.6×, `dotProduct` ≈1.5× — directly tied to how
compute-bound versus bandwidth-bound each kernel actually is. The Rust
track's `sum_min_max` kernel told a genuinely different story: a
carefully-tuned hand-written NEON kernel still measured SLOWER than
`scalar_baseline` on this host, because LLVM's own auto-vectorization of
the simple scalar loop was apparently excellent — reported honestly as
this lab's most important Rust-track finding (rust.md), not smoothed
over to match a "SIMD always wins" narrative. Both languages
independently hit and fixed the identical int32-accumulator-overflow
bug while building their `sumMinMax`/`sum_min_max` kernels (java.md,
rust.md) — the same mechanism, discovered twice, in two different type
systems. None of these exact numbers are published evidence; the
directions and mechanisms are what this lab's real, reproduced evidence
is checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/simd-vector-api-rust/code/java && mvn test
cd content/labs/simd-vector-api-rust/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java --add-modules jdk.incubator.vector -jar target/benchmarks.jar SimdLinuxEvidenceBenchmark \
  -p variant=explicitSimd -p dataset=byteClassification
cargo run --release --bin simd_vector_api_rust_evidence -- \
  --variant explicitSimd --dataset byteClassification

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh simd-vector-api-rust \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh simd-vector-api-rust \
  --profile smoke --cpus <CPU_A> --variant explicit-bytes
```

Raw JMH/Criterion output, perf stat CSVs, placement evidence and
environment metadata are produced per variant by the runner and imported
through the canonical result pipeline — numbers are never transcribed
into this page by hand.

## Limitations

- "benchmarking unsupported CPU features" is a named trap: this lab's
  Java track logs `SPECIES_PREFERRED`'s actual width rather than
  assuming AVX2/AVX-512; its Rust track uses only SSE2 (x86_64's
  guaranteed baseline, no `is_x86_feature_detected!` needed) rather than
  assuming AVX2 is present.
- "forgetting scalar tail" is a named trap: every `explicitSimd`/
  `explicit_simd` variant in this lab's matrix handles its tail
  explicitly (a `VectorMask` in Java, a scalar remainder loop in Rust) —
  `smallTailHeavyInput` exists specifically to make the tail's cost
  impossible to ignore.
- "comparing different vector widths without disclosure" is a named
  trap: this lab states its measured vector widths explicitly (128-bit
  on this dev machine, both languages) and states plainly that Java's
  `SPECIES_PREFERRED` may differ from Rust's hand-picked width on the
  native-Linux publication host — never silently assumed equal.
- "assuming SIMD helps pointer-heavy code" is a named trap: every
  dataset in this lab's matrix is a flat, contiguous primitive array or
  byte buffer — never a pointer-chasing structure — specifically so
  vectorization has a fair chance to help at all.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); this lab's Rust-track finding (hand-written SIMD
  losing to auto-vectorization for one kernel) is disclosed as a
  within-language result about compiler quality, never framed as a
  cross-language comparison.
