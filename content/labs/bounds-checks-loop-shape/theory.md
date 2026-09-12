# Bounds checks and loop shape — theory

## Performance question and hypothesis

**Question:** when can compilers eliminate bounds checks, and when does a
harmless-looking loop shape prevent it?

**Hypothesis:** canonical loops with stable limits enable range-check
elimination, while aliasing, opaque calls and irregular indexing may
retain checks or inhibit vectorization.

**What would disprove it:** if a canonical indexed loop and a loop whose
limit passes through an explicit optimization barrier cost the same, if
an irregularly-indexed loop over the identical multiset of values cost
the same as the canonical loop, or if the isolated unchecked variant
never differed from the checked ones on any dataset, the premise would be
wrong. Every variant in this lab visits either the full backing array,
the identical strided subset, or the identical slice window — never
different data — and a shared correctness oracle proves every variant
sums to the same closed-form total before any timing is trusted: **loop
shape and check-elimination eligibility change cost, never the result.**

## Learning objective

Write loop shapes the optimizer can actually prove safe, recognize when a
compiler is forced to keep a check, and know when (rarely) reaching for
`unsafe`/unchecked access is justified — and when it is not.

## Prerequisites

- The [Benchmark harness traps](/lab/benchmark-harness-traps/) and
  [Cache locality and working-set size](/lab/cache-locality-working-set/)
  labs (harness discipline and reading access-pattern effects, which this
  lab's `stridedAccess`/`irregularIndex` variants both lean on, are
  assumed here).

## Pre-lab diagnostic

Two loops sum the same array. One is `for (int i = 0; i < arr.length;
i++) sum += arr[i];`. The other is identical except the loop bound is
`int n = getLimit(); for (int i = 0; i < n; i++) sum += arr[i];`, where
`getLimit()` always returns `arr.length`. Same values, same total, same
number of iterations. Why might the second version run measurably
slower?

(Answer at the end of this page.)

## The mechanism: proving safety instead of checking it every time

- **A bounds check is a branch, and branches are not free.** Every
  `arr[i]` in Java implicitly compares `i` against `0` and
  `arr.length`; every `arr[i]` in Rust implicitly compares `i` against
  `arr.len()`. Neither language will silently read out of bounds — the
  question is not *whether* the check happens, but whether the compiler
  can **prove** it will always pass and remove it, or must **execute**
  it every iteration.
- **Range-check elimination (RCE) needs a provable relationship.** When
  a loop's index starts at a known value, increments by a known step, and
  is bounded by an expression the compiler can see is exactly the array's
  own length — the "canonical" shape — both HotSpot's C2 and LLVM can
  prove every access is in range across the *whole loop*, hoist one
  check (or zero) outside the loop, and often vectorize freely, since
  vectorization itself typically requires that same proof.
- **An opaque limit provider breaks the proof, not the correctness.** If
  the loop bound comes from a value the compiler cannot trace back to
  `arr.length` — a separate field, a call across an optimization barrier
  — the compiler can no longer rule out that the bound might exceed the
  array's real length at runtime, even if, in this program, it never
  does. The check must stay, one per iteration, and downstream
  optimizations (vectorization especially) are frequently blocked too.
  This lab constructs that barrier deliberately (`@CompilerControl
  (DONT_INLINE)` in Java, `#[inline(never)]` plus `black_box` in Rust) —
  a controlled demonstration, not a coincidence of measurement.
- **Irregular indexing removes the loop-shape proof entirely.** Indexing
  through a separate index array (`arr[idx[i]]`) gives the compiler no
  static relationship between the loop counter and the values actually
  used to index — even a perfectly in-range permutation cannot be proven
  safe from the loop's shape alone, so the check is paid every time
  *and* the access pattern itself may be cache-unfriendly (a mechanism
  this lab's `cache-locality-working-set` prerequisite already covered).
- **A safe iterator often sidesteps the question rather than answering
  it.** `for (int v : arr)` (Java) and `for v in arr.iter()` / `arr.iter().sum()`
  (Rust) never expose a raw index to reason about — the iterator itself
  guarantees every access is in range by construction, which is usually
  at least as fast as a successfully-eliminated canonical loop and is the
  idiomatic default in both languages for exactly this reason.
- **Unchecked access removes the check but not the responsibility.**
  Rust's `unsafe { *slice.get_unchecked(i) }` and reading through a raw
  `MemorySegment` offset in Java both skip the check entirely — the
  program is now responsible for an invariant the compiler no longer
  verifies. This lab isolates that code narrowly and states its
  precondition explicitly (java.md, rust.md) specifically because
  "use unsafe as the default answer" is a named trap, not a recommended
  starting point: a canonical loop the optimizer can already prove safe
  gets you the same speed with none of the risk.
- **`MemorySegment` is not automatically slower.** Java's off-heap/heap-
  segment access still does its own bounds checking by default, but nothing
  about going through `MemorySegment` inherently costs more than a plain
  array access once the JIT has warmed up — this lab measures that
  directly (java.md) rather than assuming it, because "assuming
  MemorySegment is automatically slower" is this lab's third named trap.

## Visualization 1: loop-shape transformations (deterministic)

The same operation, five shapes, side by side — this is what the
optimizer sees, not a measurement:

| Variant | Loop bound the compiler sees | Index expression | Provable in range? |
|---|---|---|---|
| canonical | `arr.length` / `slice.len()` directly | `i` | yes — RCE-eligible |
| opaqueLimit | an opaque call's return value | `i` | no — barrier hides the relationship |
| irregularIndex | `perm.length` (a *different* array's length) | `perm[i]` (arbitrary) | no — no static relationship to `arr` |
| safeIterator | none exposed | none exposed | not applicable — the iterator itself guarantees safety |
| unchecked | caller-verified once, outside the loop | `i` (or `idx[i]`) | the check is skipped, not proven |

## Visualization 2: annotated check-elimination assembly (illustrative pattern)

A generic, textbook lowering of the canonical vs. opaque-limit shapes —
**illustrative of the general pattern, not extracted from a live run of
this lab's code**; the real annotated assembly comes from `-prof
perfasm` (JMH) and `cargo asm` on the native-Linux host (benchmark.md):

```text
; canonical: ONE check hoisted before the loop, none inside it
    cmp   ecx, [arr_length]
    jg    .slow_path          ; taken only if i can ever reach length (never, here)
.loop:
    mov   eax, [arr_base + i*4]
    add   sum, eax
    inc   i
    cmp   i, ecx
    jl    .loop

; opaque limit: a check INSIDE the loop, every iteration
.loop:
    cmp   i, [arr_length]     ; the compiler cannot hoist this — it cannot
    jge   .bounds_exception   ; prove n (opaque) never exceeds arr.length
    mov   eax, [arr_base + i*4]
    add   sum, eax
    inc   i
    cmp   i, n
    jl    .loop
```

## Visualization 3: safety/performance matrix (conceptual model)

| Variant | Safety guarantee | Expected relative speed |
|---|---|---|
| canonical | full — checked, and provably always passes | fastest checked shape |
| safeIterator | full — checked by the iterator itself | at or near canonical |
| opaqueLimit | full — checked every iteration | slower than canonical |
| irregularIndex | full — checked every iteration, plus poor locality | slowest |
| unchecked | none inside the timed loop — caller's responsibility | at or near canonical, with zero margin for error |

Textual fallback for all three visualizations: a loop the compiler can
prove safe pays for at most one check; a loop it cannot prove safe pays
for one check per element; removing the check by hand only matches the
canonical loop's speed, it does not beat it — which is the direct answer
to why "use unsafe as the default answer" is a trap rather than a
strategy.

## Terminology

- **Bounds check** — the implicit comparison guaranteeing an index falls
  within `[0, length)` before a memory access.
- **Range-check elimination (RCE)** — an optimizer proving a loop's
  accesses are always in range and removing the per-iteration check.
- **Optimization barrier / opaque call** — a construct (a non-inlined
  function call, `black_box`) that prevents the compiler from tracing a
  value's relationship to another value.
- **Vectorization** — executing multiple loop iterations with one SIMD
  instruction; usually requires the same in-range proof RCE does.
- **`unsafe` / unchecked access** — skipping a safety check the language
  would otherwise perform, shifting the correctness burden to the
  programmer for that specific, narrow operation.

## Assumptions and scope

- Every dataset's backing values are `backing[i] = i`; only the access
  *order and expression* differ between variants, which is what this lab
  is actually about (theory.md's Visualization 1).
- The `irregularIndex` variant reuses the exact permutation already
  established and fixture-pinned in
  content/labs/cache-locality-working-set — the same algorithm, the same
  numbers, not a fresh generator.
- Whether a given JIT/LLVM version actually performs RCE for the
  "canonical" shape on a specific host is an empirical question this
  lab's real evidence answers (benchmark.md); the theory above states the
  textbook mechanism, not a guaranteed outcome on every toolchain version.

## Pre-lab diagnostic — answer

Even though `getLimit()` *always* returns `arr.length` in this program,
the compiler cannot see that from the loop's shape alone once the value
has crossed an opaque boundary — it can no longer prove `n <=
arr.length` for every possible future call site, so it must keep the
per-iteration bounds check (and frequently loses the ability to
vectorize the loop too). The two loops compute the identical result over
the identical data; only the compiler's ability to *prove* that in
advance differs — which is precisely this lab's hypothesis, made
concrete in two lines of code.
