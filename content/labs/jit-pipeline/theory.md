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
