# SIMD: Java Vector API and Rust — theory

## Performance question and hypothesis

**Question:** when does explicit SIMD outperform scalar code, and when
does memory bandwidth make it irrelevant?

**Hypothesis:** vectorization helps regular compute-heavy loops with
sufficient data, but tails, alignment, masks and bandwidth can dominate.

**What would disprove it:** if explicit SIMD were uniformly faster than
scalar code across every kernel and every input size in this lab's
matrix, the premise that vectorization's benefit is conditional — on
kernel shape, data volume and even which compiler is doing the
auto-vectorizing — would be wrong. Every variant reads the identical
value stream and reproduces the identical result; this lab's real
evidence (JMH/Criterion-measured, never estimated) is checked directly
against that, and — honestly — it does NOT show a uniform win (java.md,
rust.md).

## Learning objective

Identify which kernel shapes are actually vectorizable, handle a
vector's tail and target-feature availability explicitly rather than
assuming them, and separate a compute-bound kernel (where more ALU
throughput per cycle helps) from a bandwidth-bound one (where it does
not, because the CPU is waiting on memory regardless of how fast it can
add numbers).

## Prerequisites

- The [Cache locality and working-set size](/lab/cache-locality-working-set/)
  lab — this lab's "bandwidth-bound" half of its own hypothesis is that
  lab's subject applied to vector-width-scaled access patterns.
- The [Bounds checks and loop shape](/lab/bounds-checks-loop-shape/) lab
  — this lab's `autoVectorizedCandidate` variant leans on exactly the
  loop-shape sensitivity that lab establishes.

## Pre-lab diagnostic

A team rewrites a hot numeric reduction loop using explicit SIMD
intrinsics, expecting a clear win. The benchmark shows the hand-written
SIMD version is SLOWER than the plain scalar loop it replaced. Using
this lab's mechanism, name the two most likely explanations, in order of
how cheaply each can be checked.

(Answer at the end of this page.)

## The mechanism: a vector instruction is not a magic multiplier

- **A vector register processes N lanes per instruction, but every
  lane-crossing operation costs something too.** Loading N elements and
  adding them lane-wise is cheap; REDUCING a vector back to one scalar
  value (a horizontal sum, a horizontal min/max) requires cross-lane
  shuffles that are real, measurable work — not free just because they
  are a single intrinsic call. This lab's own Rust-track evidence shows
  a naive per-iteration horizontal reduce costing enough to make
  "explicit SIMD" measure SLOWER than the auto-vectorized scalar
  baseline it was meant to beat (rust.md) — a genuine, first-class
  finding, not a footnote.
- **A vector-width accumulator can silently overflow where a scalar
  accumulator would not.** A scalar `long`/`i64` sum widens naturally; a
  32-bit vector lane accumulating hundreds of thousands of additions
  does not, unless the code periodically flushes it to a wider scalar
  total. This lab's own Java AND Rust implementations both hit this
  exact bug while being built (java.md, rust.md) — the identical
  mechanism, discovered independently in each language, and fixed the
  identical way (a periodic flush-to-scalar).
- **The tail is not a rounding error — it is a real, separate code
  path.** A vector width of 4 or 16 lanes rarely divides a real dataset
  evenly; the remaining elements must be handled by a mask (this lab's
  Java Vector API `VectorMask` and its Rust scalar remainder loop) or by
  falling back to scalar entirely. `smallTailHeavyInput`'s N=17 dataset
  makes the tail THE ENTIRE WORKLOAD, not an edge case — a direct,
  deliberate stress test of this cost.
- **Auto-vectorization is a real, distinct mechanism from explicit
  SIMD, and it is not always worse.** A compiler that recognizes a
  simple, dependency-free reduction loop can auto-vectorize it — and, on
  a good backend, can do so more effectively than hand-written
  intrinsics that were not equally carefully tuned. This lab's own
  evidence shows this directly for one kernel on one host (rust.md) —
  worth teaching precisely because it contradicts the easy assumption
  that "explicit" always beats "automatic."
- **Compute-bound and bandwidth-bound kernels respond to vectorization
  completely differently.** A kernel with real per-element work (a
  range comparison, a multiply-accumulate) benefits from doing more of
  that work per cycle. A kernel that is mostly waiting on memory
  bandwidth to STREAM the data in gets little or nothing from a wider
  ALU, because the bottleneck was never the ALU. This lab's
  `byteClassification` kernel (simple comparison, dense byte data) and
  `dotProduct` kernel (real floating-point work) show real, different
  speedup magnitudes for exactly this reason (java.md).
- **A misaligned start offset is not automatically a performance
  disaster.** Both languages' SIMD APIs support unaligned loads as a
  first-class, safe operation; whether an unaligned load actually costs
  more than an aligned one is a real, host- and microarchitecture-
  dependent question this lab measures directly rather than assumes
  (`misalignedInput`, java.md).

## Visualization 1: lane-level animation (deterministic)

What one vector instruction does to N lanes of `sumMinMax`'s data — not
a measurement, the exact operation this lab's `explicitSimd` performs:

```text
lane:    [ v0 ][ v1 ][ v2 ][ v3 ]
load:    one instruction, four values
add:     sum_acc[k] += v[k]   for each lane k, in parallel
min/max: min_acc[k] = min(min_acc[k], v[k])   for each lane k, in parallel
reduce:  ONE instruction collapses 4 lanes into 1 scalar — the "horizontal" step
```

## Visualization 2: size crossover chart (illustrative pattern)

A generic sketch of how per-element cost can change with dataset size —
**illustrative of the general shape, not extracted from a live run of
this lab's code**; the real evidence is this lab's own JMH/Criterion
output (java.md, rust.md):

```text
scalar:        flat per-element cost regardless of N
explicit SIMD:  higher fixed cost (setup, mask construction), lower per-element cost once N is large enough to amortize it
                                   ^
                         the crossover point this lab's smallTailHeavyInput (N=17) sits well BEFORE
```

## Visualization 3: assembly instruction annotation (conceptual model)

What changes, kernel by kernel, between a scalar and an explicit-SIMD
build — the direct explanation for why speedup magnitude differs across
this lab's four datasets:

| Kernel | Scalar per-element work | Vectorized per-element work |
|---|---|---|
| sumMinMax | one add, two compares | one lane-add, two lane-compares — but ALSO periodic horizontal reduces |
| thresholdFilter | one compare, one branch/select | one lane-compare, one mask population-count |
| dotProduct | one multiply, one add | one lane-multiply, one lane-add (or fused), periodic reduce |
| byteClassification | two compares, one branch | two lane-compares over 16 bytes at once |

Textual fallback for all three visualizations: a vector instruction
processes many lanes per cycle for the WORK itself, but reducing lanes
back to one scalar value is its own real cost, worth minimizing by
accumulating in a register across many iterations rather than reducing
every single one.

## Terminology

- **Lane** — one of the N parallel elements a vector register holds and
  operates on simultaneously.
- **Species / vector width** — the concrete (element type, lane count)
  a vector API instance is configured for; this lab's Java track uses
  `SPECIES_PREFERRED` (the host's best available width); its Rust track
  uses a fixed 128-bit width (NEON/SSE2) explicitly, a disclosed
  asymmetry (rust.md).
- **Horizontal reduce** — collapsing a vector's N lanes into one scalar
  value (sum, min, max); a real, non-free operation.
- **Mask** — a per-lane boolean selecting which lanes an operation
  actually applies to; this lab's mechanism for handling a vector's tail
  without a separate scalar loop.

## Assumptions and scope

- Every variant reproduces the identical deterministic result over the
  identical value stream regardless of vectorization strategy (java.md,
  rust.md); ns/element, cycles/element, vector instructions and
  speedup-by-size are measured, never correctness-checked.
- `dotProduct`'s values are always small integers exactly representable
  as `double`/`f64`, specifically so summation order (lane-parallel vs.
  sequential) never changes the bit-exact result — no floating-point
  tolerance is ever needed to compare variants.
- This lab's Rust `explicit_simd` uses real `std::arch` intrinsics
  (NEON on aarch64, SSE2 on x86_64) rather than the nightly-only
  `std::simd`, per this repository's stable-toolchain policy — a real,
  disclosed constraint, not a simplification (rust.md).

## Pre-lab diagnostic — answer

The two most likely explanations, cheapest to check first: (1) the
"explicit SIMD" code reduces a vector to a scalar EVERY iteration rather
than accumulating across many iterations and reducing once — check by
reading the intrinsics code for a horizontal-reduce call inside the hot
loop, no benchmark run needed; this lab's own Rust-track evidence shows
this exact mistake costing enough to lose to the scalar baseline
(rust.md). (2) The dataset is small enough that the vector setup/tail
overhead dominates — check by re-running the SAME comparison at a
significantly larger N; this lab's `smallTailHeavyInput` variant
demonstrates exactly this shape of problem directly. Both are real,
measured findings from this lab's own development, not hypothetical
warnings.
