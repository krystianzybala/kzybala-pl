# Deoptimization and uncommon traps — theory

## Performance question and hypothesis

**Question:** what happens when optimized code assumptions stop being
true in production?

**Hypothesis:** profile-guided speculation can make common paths fast,
but profile shifts, class loading and uncommon cases can trigger
deoptimization and tail spikes.

**What would disprove it:** if a call site whose type profile shifts
mid-run cost the same as one that never shifts, if loading a new class
partway through a long run left latency unaffected, or if a rare
exception path never showed a latency spike at the moment it first
executes, the premise would be wrong. Every variant in this lab processes
the identical 1,000,000-element input stream and must accumulate the
identical, fixture-pinned total — only *when* and *how* the underlying
assumption gets violated differs. **A regime shift changes the latency
shape of a run, never its final, correctness-checked result.**

## Learning objective

Diagnose a deoptimization from real evidence rather than folklore, design
hot-path profiles that stay stable under realistic production variation,
and treat a warm-up-then-shift latency timeline as a first-class
citizen — not something averaged away.

## Prerequisites

- The [JIT pipeline](/lab/jit-pipeline/) lab (interpretation, tiered
  compilation and the concept of deoptimization are introduced there;
  this lab measures it directly) and the
  [Inlining and call-site shape](/lab/inlining-call-site-shape/) lab
  (call-site type profiles, which this lab's `profileShiftAfterWarmup`
  variant directly perturbs).

## Pre-lab diagnostic

A service runs for hours with excellent, stable p99 latency. Then, with
no code deploy and no traffic-pattern change visible in any dashboard, a
single request takes 50× the normal p99 — once — and the service returns
to its previous stable latency immediately afterward, forever. Nobody can
reproduce it on demand. Is this necessarily a bug?

(Answer at the end of this page.)

## The mechanism: assumptions that are true until they aren't

- **Optimized code is a bet, not a proof.** C2 compiles a method's hot
  path based on what it has *observed so far* — which concrete types
  appeared at a call site, which branches were taken, whether a value was
  ever null, whether an exception path ever executed. None of these are
  guarantees about the future; they are speculative assumptions the JIT
  is willing to compile against because violating them is expected to be
  rare.
- **An uncommon trap is the JIT cashing out that bet.** When a
  speculative assumption is violated at runtime — a second type appears,
  a previously-never-taken branch executes, a value that was always
  non-null turns out to be null — the compiled code cannot safely
  continue. It traps: execution falls back to the interpreter (or a lower
  tier) *at that exact point*, state is reconstructed from the optimized
  frame, and the method may be marked for recompilation with the new
  information incorporated. This transition is not free — it is the
  single most expensive individual event this lab measures.
- **Profile shift after warm-up is the textbook case.** A call site
  that saw only one concrete type throughout warm-up gets inlined and
  specialized for that type. If a *second* type only starts appearing
  after the method is already fully optimized, the assumption "this call
  site is monomorphic" is now false — the JIT must trap, then decide
  whether to recompile a more general (bimorphic-guarded, or fully
  virtual) version.
- **Late subtype loading invalidates a different kind of assumption.**
  Some optimizations rely on class-hierarchy analysis: "no subclass of
  this type exists (yet), so this call can be treated as effectively
  final." The moment a new class is loaded and linked that violates that
  closed-world assumption, any code compiled under it must be
  invalidated — even code that never directly touches the new class,
  if the JIT's reasoning depended on the *absence* of exactly this kind
  of type.
- **A rare exception path is deliberately compiled as if it never
  happens.** C2 will compile the "normal" path aggressively and leave an
  uncommon trap in place of the exception-handling machinery, on the bet
  that the exception essentially never fires. The first time it actually
  does, the trap fires, the interpreter takes over for that one
  occurrence, and — depending on how rare the JIT judges the path to
  remain — the method may or may not be recompiled to include it going
  forward.
- **A nullability shift is the same mechanism, applied to a null
  check.** A value that was observed to be non-null throughout warm-up
  can have its null check optimized aggressively; the first actual null
  triggers the same trap-and-reconsider cycle.
- **None of this exists in ahead-of-time-compiled Rust the same way.**
  LLVM's optimization decisions for a `dyn Trait` call, a `Result`'s
  error path, or an `Option`'s `None` case are all fixed **once, at
  compile time** — there is no runtime profile to consult and therefore
  nothing for a later, unexpected value to invalidate. Rust's own
  performance surprises (branch misprediction, an unpredictable `match`)
  are real, but they are a *different* mechanism with a *different* cost
  shape (a per-call statistical effect, not a one-time trap-and-
  recompile event) — this lab's fourth named trap, "equating Rust branch
  misprediction with JVM deoptimization," exists specifically to keep
  these two phenomena from being confused with each other.

## Visualization 1: assumption lifecycle diagram (deterministic)

The five variants this lab measures, and the specific assumption each one
violates — not a measurement, the textbook classification:

| Variant | Assumption made during warm-up | What violates it, and when |
|---|---|---|
| stableTypeProfile | "this call site sees one type" | nothing — the control variant, the assumption holds forever |
| profileShiftAfterWarmup | "this call site sees one type" | a second type appears starting at a fixed, later index |
| rareExceptionPath | "this path never throws" | a genuine exception fires once, at one fixed, later index |
| lateSubtypeLoading | "no other subtype exists" | a third, never-before-constructed type is used from a fixed, later index onward |
| nullabilityShift | "this value is never null" | a null-marker input appears periodically from a fixed, later index onward |

## Visualization 2: deopt spike timeline (illustrative pattern)

A generic sketch of what a batched latency timeline looks like across a
regime shift — **illustrative of the general shape, not extracted from a
live run of this lab's code**; the real evidence is this lab's own
timeline harness (benchmark.md):

```text
stable variant:        ▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁  (flat)
shifting variant:       ▁▁▁▁▁▁▁▁▁▁▁▁▁█▁▁▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂▂  (one spike, then a new steady state)
                                     ^ shift point: trap + possible recompile
```

## Visualization 3: profile-before/after comparison (conceptual model)

The qualitative relationship this lab's real evidence must confirm:

| Window | stableTypeProfile | profileShiftAfterWarmup / lateSubtypeLoading |
|---|---|---|
| pre-shift (steady) | low, flat | low, flat — identical to the control |
| at the shift point | unchanged | one or a few dramatically elevated batches (the trap itself) |
| post-shift (new steady state) | unchanged | may settle back to near-baseline, or to a new, slightly higher baseline, depending on whether recompilation produced a more general (guarded) version |

Textual fallback for all three visualizations: an assumption violated
once produces one visible spike, not a sustained slowdown, because the
JIT's response (deoptimize, reinterpret, possibly recompile) is itself a
one-time cost; whether the *new* steady state matches the old one depends
on whether the recompiled code still needs to guard against the
now-observed possibility going forward.

## Terminology

- **Uncommon trap** — the mechanism by which optimized code falls back to
  the interpreter when a compile-time speculative assumption is violated
  at runtime.
- **Deoptimization (deopt)** — the broader process of invalidating
  compiled code and reconstructing interpreter-visible state at a trap
  point.
- **Recompilation** — optionally recompiling a method with updated
  profile information after a deopt, potentially with a more general
  (less speculative) version.
- **Type profile** — the runtime history of concrete types observed at a
  call site, the basis for inline-cache and inlining decisions
  (plab-202's prerequisite territory).
- **Class hierarchy analysis (CHA)** — whole-program reasoning about
  which subtypes exist, used to justify certain devirtualization
  optimizations; invalidated by loading a new, relevant subtype.

## Assumptions and scope

- Every variant's shift point, exception index and null stride are fixed,
  documented constants (java.md) — never randomized, so a spike (or its
  absence) always appears at the same, reproducible location in the
  timeline.
- The correctness oracle checks only the final accumulated total, which
  is identical regardless of when or whether a shift occurs; the
  *timeline* (this lab's actual subject) is measured, not
  correctness-checked, because latency is not a deterministic quantity.
- The Rust track's five variants are expected to show a flat timeline
  throughout, for every variant, including the ones whose Java
  counterpart shows a real spike — see rust.md for what was actually
  measured and why that flatness is the finding, not a gap.

## Pre-lab diagnostic — answer

Not necessarily, and this lab's mechanism is exactly the kind of
legitimate, non-bug explanation worth ruling in before assuming
something is broken: a single, non-reproducible, never-repeating latency
spike with no code change and no traffic-pattern shift is consistent with
exactly one rare input finally exercising a code path the JIT had
speculatively excluded — a rare exception, a first-ever null, a
long-dormant class finally getting loaded — triggering a one-time deopt
and (if applicable) recompilation, after which the system returns to its
previous stable behavior because the assumption that mattered has now
been correctly generalized. This is precisely why "hiding warm-up phase
transitions" and "publishing only steady-state mean" are named traps: a
monitoring setup that only reports steady-state percentiles would never
even show this event, and a debugging process that assumes every anomaly
is a bug (rather than checking, with real evidence, whether it matches
this lab's mechanism) would waste time chasing a phantom.
