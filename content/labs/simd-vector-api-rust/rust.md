# SIMD: Java Vector API and Rust — Rust track

Crate `simd_vector_api_rust_lab`: `sum_min_max` / `threshold_filter` /
`dot_product` / `byte_classification` modules, each with
`scalar_baseline`, `auto_vectorized_candidate` and `explicit_simd`
(the latter dispatching to real architecture intrinsics).

## No portable SIMD on stable Rust — real architecture intrinsics instead

- **`std::simd` (portable SIMD) is nightly-only; this repository's
  toolchain is stable, and this lab adds no external SIMD crate**
  (design.md: "no unrelated frameworks or dependencies when existing
  repository mechanisms are sufficient"). `explicit_simd` therefore uses
  real `std::arch` intrinsics behind a safe function boundary: NEON
  (`std::arch::aarch64`) on aarch64, SSE2 (`std::arch::x86_64`) on
  x86_64 — SSE2 is part of every x86_64 target's guaranteed baseline, no
  runtime feature detection needed, unlike AVX2/AVX-512 — and a scalar
  fallback for any other architecture. Every `unsafe` block is isolated
  inside one small, documented function and covered directly by this
  crate's own correctness tests.
- **The SSE2 path is written to well-documented Intel intrinsics
  semantics but was not execution-verified on this repository's
  (aarch64) development machine** — this session's toolchain setup
  could not cross-compile and run an x86_64 binary locally. This is
  disclosed explicitly, matching this project's evidence-maturity
  discipline: the SSE2 path's correctness will be confirmed by this
  crate's own `cargo test` running ON the native-Linux x86_64
  publication host, the same "awaiting-native-linux-measurement" status
  every other unverified claim in this project carries.
- **NEON's horizontal-reduce intrinsics (`vaddvq_s32`,
  `vminvq_s32`/`vmaxvq_s32`) are genuinely convenient — and genuinely
  easy to reach for too often.** They directly motivated this lab's
  central Rust-track finding, below.

## A real, humbling finding while building this lab's Rust track

The first working version of `sum_min_max::explicit_simd` called
`vaddvq_s32` (a horizontal sum-reduce) INSIDE the loop, once per 4-lane
iteration — syntactically clean, and wrong to do performance-wise. Measured
against the plain `scalar_baseline` loop, this first version was
SLOWER: ≈0.264 ns/element for `explicit_simd` versus ≈0.056 ns/element
for `scalar_baseline` — nearly 5× worse, not better. The fix accumulates
`sum` in a vector register across iterations (matching how `min`/`max`
were already accumulating) and reduces to scalar only every 1,000
iterations — the identical fix the Java track needed for a DIFFERENT
reason (overflow, java.md) that turned out to also be the right
performance fix here. After the fix, `explicit_simd` improved to ≈0.159
ns/element — genuinely faster than before, but **still slower than
`scalar_baseline`'s ≈0.048 ns/element** on this specific dev machine and
kernel. This is reported honestly, not hidden: LLVM's own
auto-vectorization of a simple, dependency-free reduction loop is
apparently excellent on this target, and a hand-written NEON kernel that
was not equally carefully tuned does not automatically beat it. This is
exactly this lab's hypothesis, taken one step further than expected —
not just "tails and alignment can dominate," but "assuming hand-written
SIMD beats a mature auto-vectorizing compiler" is itself a mistake worth
naming.

## What was actually measured (dev-only, never published)

The `sum_min_max` result above was the exception, not the rule, across
this lab's four kernels on this repository's development machine
(release build, N = 1,000,000 for three kernels, 2,000,000 for
`byte_classification`): `threshold_filter` showed `explicit_simd`
(≈0.118 ns/element) beating `scalar_baseline` (≈0.143 ns/element) by
≈1.2×; `dot_product` showed a real ≈2.0× win (≈0.474 vs. ≈0.951
ns/element); `byte_classification` showed the largest win, ≈3.6× (≈0.045
vs. ≈0.161 ns/element) — the same directional pattern as the Java track
(java.md), with `byte_classification` winning most and the tightest,
simplest reduction kernel (`sum_min_max`) being the one case where
hand-written NEON did not clearly beat the compiler's own
auto-vectorization. None of these exact numbers are published evidence;
the directions and the mechanism — vectorization's benefit is
kernel-shape-dependent, never assumed uniform — are what benchmark.md's
real, reproduced evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/simd-vector-api-rust/code/rust

# correctness gate — every variant agrees on every dataset
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (five variants, sumMinMax)
cargo bench

# THE evidence tool — run directly, any variant/dataset
cargo run --release --bin simd_vector_api_rust_evidence -- \
  --variant explicitSimd --dataset byteClassification
```

## Cross-language parity notes

- This lab's Rust track uses a FIXED 128-bit vector width (NEON/SSE2)
  explicitly, while Java's `SPECIES_PREFERRED` auto-selects the host's
  best available width at runtime — a real, disclosed asymmetry
  (theory.md). On this dev machine both happen to land at 128-bit/4
  int32-lanes, a genuine, if coincidental, consistency; the
  native-Linux publication host may give Java a wider species than this
  lab's hand-picked Rust intrinsics use, which must be stated plainly
  in any published comparison, never hidden (this lab's "comparing
  different vector widths without disclosure" trap, taken seriously).
- The `sum_min_max` finding above is NOT evidence that "Rust SIMD is
  slower than Java SIMD" — it is evidence that ONE hand-written kernel,
  on ONE host, lost to ITS OWN language's compiler's auto-vectorization.
  No cross-language ranking is published from this lab (benchmark.md);
  Java is the measured side for publication numbers, and Rust's runs
  are a separately disclosed instrument.
