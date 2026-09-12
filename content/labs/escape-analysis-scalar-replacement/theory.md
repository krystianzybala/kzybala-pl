# Escape analysis and scalar replacement — theory

## Performance question and hypothesis

**Question:** when is an allocation in Java source code not an
allocation at runtime?

**Hypothesis:** non-escaping short-lived objects can be scalar-replaced
by C2, while identity, opaque calls and uncommon control flow may force
materialization.

**What would disprove it:** if a locally-used, never-escaping object cost
the same as one stored into a field, if calling `identityHashCode` never
changed a benchmark's allocation rate, or if disabling escape analysis
made no measurable difference, the premise would be wrong. Every variant
in this lab constructs one small aggregate per element and must
accumulate the *identical* total — the object's fields are always read
the same way — while only its lifetime/visibility pattern differs
(theory below). **How the object is used changes whether it is
allocated, never the computed result.**

## Learning objective

Prove, from real evidence, when an allocation has actually been
eliminated; recognize the specific code patterns that block escape
analysis; and design value-like APIs from mechanism, not superstition
("never allocate in a hot loop" is a folk rule this lab replaces with a
checkable one).

## Prerequisites

- The [JIT pipeline](/lab/jit-pipeline/) lab (tiered compilation is
  assumed here) and the [Inlining and call-site shape](/lab/inlining-call-site-shape/)
  lab — escape analysis is fundamentally a property of what has been
  inlined together; an object cannot be proven non-escaping across a
  boundary the JIT never saw through in the first place.

## Pre-lab diagnostic

A hot method allocates a small immutable coordinate object on every call,
uses its two fields to compute one number, and returns only that number.
A colleague says "that's fine, the JIT eliminates allocations like that."
Another colleague adds one line: `logger.debug("point: {}", point)` right
before the method returns. Both colleagues agree this is "just a log
line, it does nothing in production since debug logging is disabled."
Are they both right?

(Answer at the end of this page.)

## The mechanism: proving an object never needs an address

- **Escape analysis asks one question: can anything outside this
  compilation unit observe this object's identity?** If C2 can prove the
  answer is no — the object is never stored anywhere longer-lived, never
  returned across a boundary it cannot see through, never has its
  identity (address, `hashCode()`, `==`, monitor) observed — it does not
  need a heap address at all.
- **Scalar replacement is the payoff.** Once an object is proven
  non-escaping, C2 can replace it with its individual fields, held in
  registers or stack slots, exactly as if you had written the fields as
  separate local variables by hand. No allocation, no GC pressure, no
  write barrier — the object exists in source code but never in memory.
- **"Non-escaping" is scoped to what got inlined together.** Escape
  analysis operates on the *compiled graph*, not the *method as written*.
  An object passed to a method that was successfully inlined is still
  local to the analysis; the same object passed to a method that was
  **not** inlined (too large, megamorphic call site, explicitly
  `DONT_INLINE`) crosses a boundary C2 cannot see through, and the
  conservative, correct answer is: assume it escapes.
- **Returning an object is escaping by construction — usually.** A
  method that returns a freshly-built object hands a reference to its
  caller; whether that still counts as "local" depends entirely on
  whether the *caller* also keeps it local and the call itself got
  inlined. This lab's `returnedObject` variant routes construction
  through a real (non-inlined) call specifically so the escape is real
  and reproducible, not an artifact of over-eager inlining hiding the
  effect.
- **Storing into a field is unconditionally escaping.** Once a reference
  is reachable from anything with a lifetime longer than the current
  stack frame — an instance field, a static field, a collection — no
  local analysis can bound how long it lives or who might read it next.
  This is GlobalEscape, the strongest and simplest case: always
  materialized.
- **Identity is incompatible with scalar replacement, deliberately.** A
  scalar-replaced object has no single address — its fields might live in
  different registers, or not exist as memory at all between uses. Code
  that asks for the object's identity (`System.identityHashCode`,
  `synchronized` on it, reference equality against another object)
  is asking a question scalar replacement cannot answer, so the compiler
  must materialize a real object to answer it truthfully.
- **Synchronization elimination is a related, distinct optimization.**
  If C2 proves a lock's target never escapes the current thread, it can
  remove the lock acquisition/release entirely (no other thread could
  ever contend for it) — independent of whether the object is also
  scalar-replaced. An object can lose one optimization without losing the
  other, which is why "identity hash or synchronization" names two
  related-but-separate mechanisms, not one.
- **Rust never asks this question for a plain value.** A Rust struct
  without an explicit `Box`/`Rc`/`Vec` is a stack (or register) value from
  the moment it is written — there is no analysis proving it *can* avoid
  the heap, because the heap was never the default to begin with. Rust's
  explicit heap-boxing (`Box::new`) is the closest match to Java's
  *always-heap-unless-proven-otherwise* default — comparing an
  un-boxed Rust value to a materialized Java object is comparing two
  different semantic starting points, this lab's fourth named trap.

## Visualization 1: escape graph (deterministic)

The five usage patterns this lab measures, and what each one means for
C2's escape classification — not a measurement, the textbook
classification:

| Variant | What crosses a boundary | Escape classification |
|---|---|---|
| nonEscaping | nothing — read and discarded locally | NoEscape — eligible for scalar replacement |
| returnedObject | a reference, across a real (non-inlined) call | ArgEscape/GlobalEscape depending on the caller — treated as escaping here by construction |
| storedIntoField | a reference, into a longer-lived field | GlobalEscape — always materialized |
| passedToOpaqueCall | a reference, across a call C2 cannot see into | conservatively escaping — materialized regardless of what the callee actually does |
| identityObserved | the object's identity itself | incompatible with scalar replacement by definition |

## Visualization 2: allocation elimination report (illustrative pattern)

A generic sketch of what JFR/`-prof gc` reports for the eliminated vs.
materialized cases — **illustrative of the general shape, not extracted
from a live run of this lab's code**; the real evidence is `-prof gc`,
JFR allocation profiling and, where available, `-XX:+PrintEscapeAnalysis`
(benchmark.md):

```text
nonEscaping:          gc.alloc.rate.norm ≈ 0 B/op    (scalar-replaced)
storedIntoField:       gc.alloc.rate.norm ≈ (header + fields) B/op   (materialized every call)
```

## Visualization 3: latency/allocation crossover (conceptual model)

The qualitative relationship this lab's variants are built to
demonstrate:

| Variant | Allocation rate | Relative latency | Why |
|---|---|---|---|
| nonEscaping | ~zero | lowest | scalar-replaced — no heap traffic, no GC involvement |
| storedIntoField / returnedObject / passedToOpaqueCall | one object per call | higher | real allocation, real GC bookkeeping, possible collection pauses |
| identityObserved | one object per call, **plus** an identity operation | highest of the materialized group | pays the allocation cost *and* whatever the identity/lock operation itself costs |

Textual fallback for all three visualizations: an object that never
leaves its method, is never returned across a real call boundary, never
stored anywhere longer-lived, and never has its identity observed can be
compiled away entirely; any one of those four conditions failing forces
a real allocation, and identity observation is the one condition scalar
replacement can never satisfy, regardless of how short-lived the object
otherwise is.

## Terminology

- **Escape analysis** — the compiler analysis proving (or failing to
  prove) that an object's lifetime and visibility are bounded to the
  current compilation unit.
- **Scalar replacement** — eliminating an object allocation by replacing
  it with its individual fields once escape analysis proves it safe.
- **NoEscape / ArgEscape / GlobalEscape** — HotSpot's escape
  classifications, from "never leaves this method" to "reachable from
  anywhere."
- **Synchronization elimination (lock elision)** — removing a lock
  acquire/release once escape analysis proves the lock target is
  thread-local; related to, but distinct from, scalar replacement.
- **Materialization** — the point at which an object that *could* have
  been scalar-replaced is instead given a real heap allocation, because
  some code path required an actual object.

## Assumptions and scope

- Every variant's per-element contribution (`x + y`) is identical; only
  the aggregate's lifetime/visibility pattern differs, which is what this
  lab is actually about (Visualization 1).
- `identityObserved` calls `System.identityHashCode` for its side effect
  only — the non-deterministic hash value is never included in the
  correctness-checked total (java.md).
- The Rust track's five variants use a plain, unboxed value type
  throughout; `sum_boxed` (rust.md) is a supplementary reference point
  for genuine heap-allocation cost, not part of the cross-language
  correctness matrix — comparing it directly to Java's numbers would
  itself be an instance of this lab's "comparing stack Rust value to heap
  Java object with different semantics" trap unless the comparison is
  made explicit, which is exactly what rust.md does.

## Pre-lab diagnostic — answer

They are both wrong, in a specific and checkable way. The first claim is
conditionally true (a non-escaping coordinate object *can* be
scalar-replaced) — but the second colleague's "does nothing" claim about
the disabled log line is the actual trap: even with debug logging
disabled at the logging-framework level, the *call itself*
(`logger.debug(...)`, and the varargs array or string-formatting
machinery Java builds to prepare its arguments) still exists in the
compiled bytecode as a real method call the JIT must reason about.
Whether that specific call is small enough and hot enough to be inlined
away is a real, checkable question — not a given — and if it is not
inlined, the coordinate object passed into it now crosses an opaque call
boundary, identical in effect to this lab's `passedToOpaqueCall` variant,
and loses eligibility for scalar replacement regardless of whether the
log statement ever prints anything. The fix is not "trust that disabled
logging is free" — it is checking, with real evidence (benchmark.md),
whether the specific call in the specific hot path actually got inlined.
