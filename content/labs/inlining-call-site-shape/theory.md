# Inlining and call-site shape — theory

## Performance question and hypothesis

**Question:** how do monomorphic, bimorphic and megamorphic call sites
change optimization opportunities?

**Hypothesis:** inlining unlocks constant propagation and scalar
replacement, but call-site diversity and code-size budgets can block it.

**What would disprove it:** if a call site that only ever sees one
concrete type cost the same as one that round-robins through six, if a
manually-inlined switch/enum dispatch never beat an equivalent virtual
call, or if a callee too large to inline performed identically to a
small one computing the same result, the premise would be wrong. This
lab's oracle is pairwise, not a single shared total: `monomorphic` must
equal `oversizedCallee` (same logical strategy, different code size) and
`megamorphic` must equal `switchDispatch` (same six-way assignment,
different dispatch mechanism) — **dispatch mechanism and code size
change cost, never the result.**

## Learning objective

Identify a call site's shape from its code, connect inlining eligibility
to the optimizations it unlocks downstream, and reason about the
trade-off between specialization and code-cache/i-cache pressure.

## Prerequisites

- The [JIT pipeline](/lab/jit-pipeline/) lab (interpretation, C1/C2 tiers
  and warm-up are assumed here — this lab is specifically about what C2
  does *at* a call site once a method is hot).

## Pre-lab diagnostic

A team adds a fifth pricing strategy to a system that previously had
exactly two, deployed behind the same interface and the same call site
that has run in production for a year. No other code changes. Throughput
drops noticeably. Nobody added new work per call. What changed?

(Answer at the end of this page.)

## The mechanism: what a call site remembers, and what defeats it

- **Inlining is not a size optimization — it is what enables every
  optimization downstream.** Once a callee's body is inlined into its
  caller, the compiler can propagate constants across the former call
  boundary, eliminate branches whose outcome becomes provable, and — if
  the strategy object itself never escapes — allocate it on the stack or
  skip allocating it entirely (scalar replacement). None of that is
  possible across an un-inlined call: the compiler cannot see through it.
- **A call site accumulates a runtime type profile, and the profile
  decides.** HotSpot's C2 tracks, per call site, how many distinct
  concrete receiver types it has actually seen. A **monomorphic** site
  (one type, ever) gets speculatively inlined outright — the fastest
  path, and reversible only if a new type shows up later
  (deoptimization). A **bimorphic** site (exactly two types) still gets
  inlined, guarded by a type check per call. A **megamorphic** site
  (commonly more than the JIT's inline-cache capacity — a small constant,
  not "many" in the abstract) gives up on inlining and falls back to a
  full virtual (vtable) dispatch for every call.
- **A manual switch/enum dispatch never enters this profile at all.**
  Selecting behavior with a closed `switch` (Java) or `match` (Rust)
  compiles to a jump table or a chain of compares — there is no
  polymorphic call site to profile, and no megamorphic fallback to fear,
  because there was never a virtual call to begin with. This is why
  "manual switch/enum dispatch" is a genuinely different mechanism from
  "a call site that happens to be monomorphic," not just another point on
  the same spectrum.
- **Code size is a second, independent inlining gate.** Even a perfectly
  monomorphic call site will not be inlined if the callee's body exceeds
  the compiler's size budget (HotSpot's `MaxInlineSize`/`FreqInlineSize`;
  LLVM's cost-model thresholds). This lab's `oversizedCallee` variant
  holds call-site shape constant (always the same logical strategy) and
  varies only code size, to isolate this second gate from the first.
- **Rust's `dyn Trait` and Java's interface call are not the same
  mechanism, even though both look like "a virtual call."** Java's JIT
  makes its inlining decision *at runtime*, from an observed type
  profile, and can change its mind (deoptimize) if the profile shifts.
  Rust's `dyn Trait` dispatch is decided *at compile time* — it is always
  a vtable call, and LLVM never inlines through it no matter how uniform
  the runtime types turn out to be. Rust's compile-time answer to "I want
  the monomorphic case to be free" is **generics/monomorphization**, a
  structurally different mechanism (a distinct compiled function per
  concrete type, decided at compile time, not runtime profiling) —
  conflating the two is this lab's explicitly named trap ("comparing
  trait object to Java sealed dispatch as identical").
- **Inlining trades specialization for code size, and code size has its
  own cost.** Aggressively inlining every call site grows the compiled
  code footprint, which can push hot code out of the instruction cache —
  "ignoring code-cache/i-cache cost" is this lab's fourth named trap
  precisely because a synthetic microbenchmark's tiny working set of code
  rarely reproduces that pressure the way a large real application does.

## Visualization 1: call-site profile diagram (deterministic)

The four dispatch mechanisms this lab measures, and what the compiler
records for each — not a measurement, the textbook classification:

| Call-site shape | Distinct types seen | Compiler's response |
|---|---|---|
| monomorphic | 1 | speculatively inline, guard with one cheap check |
| bimorphic | 2 | inline both, guard with a two-way check |
| megamorphic | more than the inline-cache capacity | give up — full virtual dispatch, no inlining |
| switch/enum dispatch | n/a — no virtual call exists | ordinary branch/jump-table optimization, no profile needed |

## Visualization 2: inlining tree (illustrative pattern)

A generic sketch of what gets inlined into the caller for the monomorphic
vs. megamorphic case — **illustrative of the general shape, not extracted
from a live run of this lab's code**; the real evidence is `-prof
perfasm` / `cargo asm` and, where available, JITWatch's inlining log
(benchmark.md):

```text
monomorphic (inlined):              megamorphic (not inlined):
sumMonomorphic()                    sumMegamorphic()
├─ loop                             ├─ loop
│  └─ AddOne.apply(x)  [INLINED]    │  └─ invokeinterface apply(x)  [vtable call]
│     └─ x + 1  [visible to C2]     │     (callee body opaque to the caller's
│                                   │      optimizer — nothing downstream
│                                   │      can see across this boundary)
```

## Visualization 3: performance vs. code-size chart (conceptual model)

The qualitative trade-off this lab's variants are built to demonstrate —
the direction the real evidence must confirm, not a measurement:

| Variant | Relative speed | Relative code size at the call site |
|---|---|---|
| monomorphic | fastest | smallest (one inlined body) |
| bimorphic | slower | small (two inlined bodies + a guard) |
| switchDispatch | fast, comparable to monomorphic | small–moderate (one jump table, all bodies visible) |
| megamorphic | slowest | smallest AT the call site itself (nothing inlined) — but pays a real vtable-dispatch cost every call |
| oversizedCallee | slower than monomorphic despite identical shape | the callee itself is large; nothing is inlined regardless of shape |

Textual fallback for all three visualizations: a call site's inlining
outcome depends on two independent questions — how many concrete types
has it actually seen, and is the callee small enough to inline even if
the shape allows it — and a manual switch/enum sidesteps the first
question entirely by never creating a polymorphic call site.

## Terminology

- **Call-site shape** — the count of distinct concrete receiver types a
  specific call site has observed at runtime (monomorphic/bimorphic/
  megamorphic).
- **Inline cache** — the mechanism a JIT uses to cache and guard a
  speculative inlining decision at a polymorphic call site.
- **Scalar replacement** — eliminating an object allocation entirely by
  replacing it with its individual fields, only possible once escape
  analysis (itself often gated on inlining) proves the object never
  escapes.
- **Deoptimization** — reverting a speculatively-compiled, inlined method
  back to the interpreter/lower tier when a runtime assumption (such as
  "this call site is monomorphic") is violated.
- **Monomorphization** (Rust) — generating a separate compiled function
  per concrete type a generic function is instantiated with, at compile
  time — Rust's structural alternative to runtime speculative inlining.

## Assumptions and scope

- The `oversizedCallee` variant's bloated method body is functionally
  identical to `monomorphic`'s single strategy (`x + 1`); the correctness
  oracle requires their totals to match exactly (java.md, rust.md).
- `megamorphic` and `switchDispatch` apply the identical six-way
  round-robin assignment; their totals must match exactly, isolating
  dispatch mechanism as the only variable.
- Rust's `dyn Trait` variants are not expected to reproduce Java's
  inlining-driven cost curve — see the crate-level doc comment in
  `code/rust/src/lib.rs` and rust.md for what Rust's mono/bimorphic/
  megamorphic gradient (if any) actually measures instead.

## Pre-lab diagnostic — answer

The call site's shape almost certainly changed from bimorphic to
megamorphic (or crossed whatever threshold the JIT's inline-cache
capacity enforces). Nothing about the *amount of work per call* changed —
only how many distinct concrete types now flow through that one call
site. Once the count exceeds the JIT's capacity for a guarded, inlined
dispatch, it falls back to a full virtual call for every invocation,
losing not just the direct call overhead but every optimization that
depended on the call being inlined in the first place — exactly this
lab's hypothesis, produced by adding a single new class to production
code that looked, by every other measure, unrelated to performance.
