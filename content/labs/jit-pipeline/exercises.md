# JIT pipeline — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the benchmark that "proves" Java got slower)

A colleague benchmarks a service method by timing 1,000 calls in `main()`
and reports the average. After a refactor that split one hot method into
three smaller ones, their number got **worse**, and they conclude the
refactor hurt performance. Their harness starts a fresh JVM per
measurement and reports one aggregate mean over all 1,000 calls.

**Task:** explain why this number is dominated by the warm-up mixture
rather than steady-state cost, why the refactor can *look* slower in it
while being faster in steady state, and design the minimal experiment
separation that answers the actual question.

**Success criteria:** you name which pipeline phases the 1,000 calls
span; you can state at least two mechanisms by which more (smaller)
methods change the *trajectory* (per-method invocation thresholds,
inlining decisions) without changing steady state; and your redesign
produces a trajectory view and a steady-state view as separate results.

<details>
<summary>Hint</summary>

At 1,000 total calls, has C2 even run? Check what a compilation log shows
around that call count for a method with default tier thresholds — then
look at what the lab's warm-up harness reports for its first segments.

</details>

<details>
<summary>Solution</summary>

1,000 cold calls sit almost entirely in the interpreter/C1 segment of the
trajectory — the aggregate mostly measures profiling overhead and
early-tier code quality. Splitting a method resets per-method counters (three methods
each need their own invocations to climb tiers) and changes inlining
shape, so the *early mixture* shifts even when C2 steady state ends up
equal or better. The valid design is this lab's separation: a cold-JVM
trajectory harness (per-block series + compilation log) for the warm-up
question, and a JMH steady-state benchmark for the post-warm-up question —
never one number spanning both.

</details>

## Exercise 2 — Implementation (make a deopt happen on purpose, then read it)

Using the lab's Java project, extend `DeoptTrajectoryHarness` with a
fourth phase that returns to the pure monomorphic workload after the
polluted phase.

**Success criteria (measure, don't assert):**

1. All fixture totals stay exact in every phase (the existing checks must
   pass on your extension).
2. Your run's compilation log shows the original compilation "made not
   entrant" during the polluted phase and at least one later recompilation.
3. You can answer from your series, not from theory: does phase 4 return
   to phase-1 cost, or does the call site stay polymorphic once polluted?
   Cite the block timings and the log entries that support your answer.

<details>
<summary>Hint</summary>

Type profiles are sticky: the receiver set a site has *seen* does not
shrink when the workload becomes monomorphic again. Whether the optimizer
re-speculates depends on JVM version and flags — which is exactly why the
success criterion asks for your log, not a textbook answer.

</details>
