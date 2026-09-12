# JIT pipeline — theory

## Performance question and hypothesis

**Question:** how does Java code move from bytecode through tiered
compilation, and why do benchmark results change during warm-up?

**Hypothesis:** the same method's cost is a trajectory, not a number — it
falls in steps as execution moves from the interpreter through C1 to C2,
can spike back when speculation fails (deoptimization / uncommon traps),
and only then settles into a steady state; any single aggregate that spans
those phases measures the mixture, not the code.

**What would disprove it:** if per-block timings from a cold JVM showed no
step-shaped improvement aligned with compilation-log events; if polluting
a hot monomorphic call site with new receiver types produced no transient
cost spike and no "made not entrant" recompilation entries; or if steady-
state costs did not differ across call-site shapes (mono/bi/megamorphic)
or with escape analysis disabled — then the tiered-pipeline model taught
here would be wrong. Each claim has its own dedicated experiment, so each
is separately falsifiable.

## Learning objective

Explain the tiers a hot method moves through (interpreter → C1 profiled →
C2 speculative), read a warm-up trajectory and a compilation log side by
side, recognize deoptimization as a normal, observable event rather than a
failure, and know which comparisons are valid: warm-up trajectories are
Java-only phenomena; a natively-compiled (AOT) baseline is a separate
scenario, never a competitor to a warming JVM.

## Pre-lab diagnostic

Two engineers benchmark the same method. One times the first 50 calls
right after JVM startup and reports 40 µs/call. The other runs the method
in a loop for ten seconds first, then times the next 50 calls, and
reports 0.4 µs/call — a 100x difference, same code, same input. Neither
measurement is broken. What is actually different between what the two
numbers describe?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Tier | One of the JVM's execution strategies for a method: interpreter, C1 (client), C2 (server) — each progressively more optimized and progressively more expensive to produce. |
| Warm-up | The period during which a method is still moving through tiers; its measured cost is a mixture of interpreter, C1 and C2 execution, not a single steady value. |
| Steady state | The cost once a method has settled at its final tier for the current run and profile — the only point at which a single aggregate number is a meaningful "cost per call." |
| Speculative optimization | A C2 optimization that assumes something not guaranteed by the language (e.g. "this call site only ever sees one receiver type") and compiles code that is only correct while that assumption holds. |
| Uncommon trap | The guard C2 inserts to detect a speculative assumption breaking at runtime. |
| Deoptimization ("deopt") | What happens when an uncommon trap fires: execution falls back from compiled code to the interpreter for that frame, the compiled method is marked "made not entrant," and (usually) a less speculative recompilation follows. |
| Call-site shape | How many distinct receiver types a virtual call site has actually observed: monomorphic (one), bimorphic (two), megamorphic (many) — directly determines whether C2 can inline it. |
| AOT (ahead-of-time) compilation | Rust's model: all machine code is generated before the program ever runs, using no runtime profile — contrasted here with the JVM's profile-driven, runtime-adaptive pipeline, never compared to it as a "faster/slower" claim. |

## Required visualization: tiered compilation timeline

A conceptual timeline of one hot method's tier transitions over its
invocation count, aligned with the compilation-log events that cause
each step:

```
cost/call
   |
40x|██
   |██
   |██▓▓
 4x|  ▓▓▓▓
   |      ▓▓▓▓░░░░
 1x|              ░░░░░░░░░░░░░░░░░░░░░░░░░░
   +--------------------------------------------> invocation count
     interp    C1 (profiled)      C2 (optimized)
     ^invocation #1        ^tier-up ~ a few thousand calls (compilation-log event)
```

### Textual fallback (tiered compilation timeline)

| Invocation range | Active tier | Relative cost/call | Triggering event |
|---|---|---|---|
| First calls | Interpreter | ~40x steady state | JVM start; no profile yet |
| Early hundreds–thousands | C1 (profiled) | ~4x steady state | Interpreter invocation count crosses the C1 compile threshold (compilation-log entry) |
| After sufficient profile | C2 (optimized) | 1x (steady state, by definition) | C1 invocation/back-edge counters cross the C2 threshold (compilation-log entry) |

The lab's `WarmupTrajectoryHarness` records the real per-block series this
diagram summarizes conceptually — see java.md and benchmark.md for the
actual (currently awaiting-native-Linux-measurement) data.

## Required visualization: latency-by-iteration plot

A conceptual view of per-call latency plotted against call sequence
number, the same trajectory as above but from the caller's perspective
rather than the compiler's:

```
latency
   |*
   |**
   | ***
   |    ****
   |        *****................................
   +--------------------------------------------> call sequence
```

### Textual fallback (latency-by-iteration)

| Region | Shape | What it means |
|---|---|---|
| Start | High, noisy | Interpreter dominance; every call pays full bytecode-dispatch cost |
| Descent | Sharp, step-like drops | Tier transitions (C1, then C2) — not a smooth curve, because a tier change is a discrete event |
| Plateau | Flat, low-noise | Steady state — the only region a single ns/op number honestly describes |

## Required visualization: deoptimization event overlay

Overlaying a deoptimization event onto the latency-by-iteration plot for
a call site that starts monomorphic, gets polluted with new receiver
types, and later stabilizes:

```
latency
   |                    ^ deopt (uncommon trap fires)
   |                    |
   |....................|**
   |                    |  ***
   |____________________|     *****...............
   +--------------------------------------------> call sequence
      steady (compiled)   pollution   recompiled steady state
```

### Textual fallback (deoptimization event overlay)

| Phase | Latency shape | Compilation-log signature |
|---|---|---|
| Monomorphic steady state | Flat, low | (compiled once; no further log entries) |
| Pollution (new receiver types appear) | Sharp spike at the transition | Guard failure → uncommon trap → "made not entrant" on the old compilation |
| Recompiled steady state | Flat, but not necessarily as low as before | A new compilation-log entry for the same method, now guarding a wider (or no longer speculative) assumption |

This is exactly what the lab's `DeoptTrajectoryHarness` measures directly
(mono-hot → polluted → post-recompile phases) rather than illustrates —
see exercises.md's Exercise 2 for extending it with a fourth phase.

## The pipeline, phase by phase

1. **Interpreter** — every bytecode dispatched individually; profiling
   counters accumulate (invocation counts, branch profiles, receiver
   types).
2. **C1 (client compiler)** — quick, lightly-optimized machine code with
   profiling instrumentation left in.
3. **C2 (server compiler)** — aggressive optimization built on the
   collected profile: inlining decisions driven by call-site shape,
   escape analysis that scalar-replaces non-escaping allocations,
   speculative assumptions guarded by uncommon traps.
4. **Deoptimization** — a guard fails (e.g. a new receiver type at a
   call site compiled as monomorphic): the frame falls back to the
   interpreter, the compiled method is "made not entrant", profiling
   resumes, and a more conservative compilation follows.

## Common mistakes and benchmark traps

- **Collapsing startup, warm-up and steady state into one number** — the
  lab's trajectory harness exists precisely because JMH's design discards
  the trajectory.
- **Comparing JVM warm-up against Rust steady state** — the AOT baseline
  has no tiers to climb; it is a separate scenario by policy.
- **Treating deoptimization as noise** — it is an event with a cause,
  visible in the compilation log.
- **Benchmarking a call site whose shape differs from production** — a
  monomorphic microbenchmark of a megamorphic production site measures an
  inlining outcome production never sees.

## Limitations

Tier thresholds, inlining heuristics and trap behavior are JVM-version-
and flag-dependent; every published run records the exact JVM build,
flags and compilation log rather than assuming defaults.

## Assumptions and scope

- All variants operate on the same deterministic `PricingKernel` workload
  (shared fixture, exact integer semantics) — the lab's claims are about
  the compilation pipeline's effect on that one workload's cost
  trajectory, not a general "how much does JIT help" number.
- The tiered-compilation timeline and latency-by-iteration diagrams above
  are conceptual models for teaching the *shape* of the effect; the real,
  disclosed measurement is the per-block series `WarmupTrajectoryHarness`
  records, not these illustrations (see benchmark.md's awaiting-native-
  Linux-measurement state).
- The Rust `aot_baseline` is a separate scenario by design, never merged
  into a warm-up or steady-state comparison table with the Java tiers —
  see rust.md for why an AOT binary's "warm-up" (cache/frequency
  stabilization only) is not the same phenomenon.
- Deoptimization behavior (whether a call site re-speculates after
  pollution ends) is JVM-version- and flag-dependent; this lab reports
  what its own recorded compilation log shows for one run, never a
  universal claim about HotSpot's re-speculation policy.

## Pre-lab diagnostic — answer

Both numbers are real measurements of the same code — they describe
different *phases* of that code's execution, not different implementations
or different machines. The first engineer measured the interpreter (and,
depending on the exact call count, possibly early C1) phase: every one of
those first 50 calls is dispatched bytecode-by-bytecode with no compiled
machine code backing it yet, which is exactly why it costs roughly 40x
more than the eventual steady state described in this lab's tiered-
compilation timeline above. The second engineer's ten-second warm-up loop
gave the method enough invocations to climb through C1 and reach C2's
fully-optimized, speculatively-inlined machine code before the timed
calls ever started — so their 50 calls measure steady state. Averaging
either engineer's number with the other's, or reporting just one of them
as "the" cost of the method, would misrepresent a trajectory as a
constant; the correct answer to "how fast is this method" is the shape
in the latency-by-iteration plot above, not either endpoint alone.
