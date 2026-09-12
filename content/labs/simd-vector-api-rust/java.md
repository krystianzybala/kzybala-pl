# SIMD: Java Vector API and Rust — Java track

Package `pl.kzybala.lab.simd`: `SimdFixtures` (deterministic source-data
generation), `SumMinMaxKernel` / `ThresholdFilterKernel` /
`DotProductKernel` / `ByteClassificationKernel` (three or five variant
methods each, using the incubating `jdk.incubator.vector` API).

## The pieces

- **`jdk.incubator.vector` is an incubating JDK module** — every
  compile, test and run step needs `--add-modules jdk.incubator.vector`
  explicitly; there is no Maven artifact for it. This lab's `pom.xml`
  wires the flag into `maven-compiler-plugin`, `maven-surefire-plugin`
  and every JMH `@Fork`'s `jvmArgsAppend`.
- **`scalarBaseline`** — a plain loop. Whether C2 auto-vectorizes it is
  JIT-version- and host-dependent and is never assumed — this lab's own
  measured evidence (below) shows it clearly did NOT get the same
  treatment as `autoVectorizedCandidate`'s shape on this dev machine.
- **`autoVectorizedCandidate`** — the canonical single-accumulator,
  branch-minimal counted-loop shape superword auto-vectorization
  targets (`Math.min`/`Math.max` instead of if-statements; the
  branchless `(v > threshold) ? 1 : 0` counting idiom).
- **`explicitSimd`** — `SPECIES_PREFERRED` (128-bit / 4 int lanes on
  this dev machine, confirmed empirically, never assumed), a
  full-width vector loop, and a `VectorMask`-handled tail — no separate
  scalar tail loop for the common case.
- **`misalignedInput`/`smallTailHeavyInput`** — the IDENTICAL
  `explicitSimd` kernel run over a different input SHAPE (offset by one
  element; truncated to 17 elements), matching this lab's
  one-operation-per-variant contract by delegating to shared code.

## A real bug found while building this lab

`SumMinMaxKernel.explicitSimd`'s first version accumulated `sum` inside
an `IntVector` register across ALL iterations, reducing to a scalar only
once at the end — this is the textbook-optimal pattern (avoid frequent
horizontal reduces). It produced the WRONG answer: each of the 4 lanes
accumulates roughly 250,000 additions averaging ~500,000 each (≈125
billion per lane) — massively beyond `int`'s ~2.1 billion range, so the
lane silently wrapped. A scalar `long sum` never has this problem
because it widens automatically; a 32-bit vector accumulator does not.
The fix flushes the vector accumulator into a scalar `long` total every
1,000 vector iterations (comfortably below overflow for this dataset's
value range) and resets it to zero — the SAME fix, discovered
independently, that the Rust track needed (rust.md).

## A real finding from development wiring (dev-only, never published)

Running this lab's four kernels on this repository's development
machine (JMH, N = 1,000,000 for three kernels, 2,000,000 for
`byteClassification`) showed a consistent, real speedup from
`explicitSimd` over `scalarBaseline` in every case — but a DIFFERENT
magnitude per kernel, directly tied to how much real per-element work
each kernel does: `sumMinMax` ≈4.0× (0.652 → 0.163 ns/element),
`thresholdFilter` ≈3.6× (0.637 → 0.179 ns/element), `dotProduct` ≈1.5×
(0.948 → 0.622 ns/element — the smallest speedup, consistent with
`dotProduct`'s FMA-style work being closer to memory-bandwidth-bound
than the others), and `byteClassification` the largest by far, ≈51.5×
(2.342 → 0.045 ns/element — 16 bytes processed per vector instruction
instead of 1, the widest lane count of any kernel in this lab's
matrix). `misalignedInput` (0.163 ns/element) measured within rounding
of `explicitSimd`'s own aligned result — no measurable misalignment
penalty on this run, a real, honest result, not an assumption.
`smallTailHeavyInput` (N = 17) measured 5.91 ns total (≈0.348
ns/element) — noticeably WORSE per-element than the full-size
`explicitSimd` run (0.163 ns/element), direct evidence that the tail's
fixed overhead dominates at small N, exactly this lab's hypothesis.
None of these exact numbers are published evidence; the directions are
what benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/simd-vector-api-rust/code/java

# correctness gate — every variant agrees on every dataset
mvn test

# build
mvn -q -DskipTests package

# dev/wiring JMH: sumMinMax (unpinned) — note --add-modules on the OUTER java too
java --add-modules jdk.incubator.vector -jar target/benchmarks.jar SimdBenchmark

# THE full-matrix publication benchmark (pinned, via the native-Linux runner)
java --add-modules jdk.incubator.vector -jar target/benchmarks.jar SimdLinuxEvidenceBenchmark \
  -p variant=explicitSimd -p dataset=byteClassification
```

## Reading the results

- Always confirm `SPECIES_PREFERRED`'s actual width on the measured host
  before drawing any conclusion — this lab's `theory.md` states the
  128-bit width on this dev machine explicitly; the native-Linux
  publication host may report a wider species (256-bit AVX2, for
  example), which would change EVERY kernel's expected speedup ceiling.
  Never assume; log `SPECIES.toString()`.
- Compare `misalignedInput` against `explicitSimd` on the SAME kernel
  and host — this lab's own result showing no penalty is specific to
  this dev machine's JDK build and CPU; it is not a general claim that
  misalignment never costs anything.
- `byteClassification`'s outsized speedup (≈51.5×) is a real, mechanism-
  driven result (16 lanes vs. 4), not an outlier to discount — read it
  alongside `dotProduct`'s much smaller speedup to see the
  compute-bound-vs-bandwidth-bound distinction directly.
