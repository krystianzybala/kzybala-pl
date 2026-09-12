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

## Exercise 3 — Evidence interpretation (reading a compilation log against a latency trajectory)

Below is a **synthetic teaching example** — a fabricated, condensed
compilation-log excerpt paired with a per-block latency series,
constructed for this exercise, not captured from any real run. It is
educational material for practicing log/trajectory correlation only: it
is never used as measurement evidence, never supports this lab's
performance conclusions, and never enters a comparison or maturity
calculation. (This lab's real trajectory and compilation-log evidence
comes exclusively from `WarmupTrajectoryHarness`/`DeoptTrajectoryHarness`
run on the native-Linux evidence host; see benchmark.md.)

```
Compilation log (condensed, timestamps in ms since JVM start):
  412   COMPILE   PricingKernel.priceMono (C1)
  1889  COMPILE   PricingKernel.priceMono (C2)
  6104  MAKE-NOT-ENTRANT  PricingKernel.priceMono  reason=class_check
  6110  UNCOMMON-TRAP  PricingKernel.priceMono  reason=unstable_if
  7350  COMPILE   PricingKernel.priceMono (C2)  note=guard widened

Per-block latency (ns/call, one block = 1,000 calls, blocks 1-10):
  1: 41,200   2: 6,800   3: 410   4: 210   5: 205
  6: 198   7: 5,650   8: 640   9: 215   10: 202
```

**Task:** identify which block(s) correspond to which compilation-log
event, and explain the latency at block 7 using the log — not just "it
went up."

**Success criteria:** you correctly match at least three distinct latency
regions to their log-explained cause, and your explanation of block 7
names the specific mechanism (not just "recompilation happened") using
the reordering/ordering vocabulary this lab and its prerequisites use
(guard, speculative assumption, uncommon trap).

<details>
<summary>Hint</summary>

Block 1 is far more expensive than block 2, which is itself far more
expensive than blocks 3 onward — that is two separate step-downs, not
one. Now find the *single* block where cost goes back up after having
already been low, and look for a log entry timestamped in that
neighborhood.

</details>

<details>
<summary>Solution</summary>

- **Block 1** (41,200 ns): pure interpreter — no compilation has happened
  yet (`C1` doesn't fire until 412 ms, and given roughly comparable
  wall-clock pacing across blocks in this synthetic series, block 1 falls
  before that first compile event).
- **Block 2** (6,800 ns): C1-compiled but not yet C2-compiled — the drop
  from block 1 lines up with the `(C1)` compile entry; still an order of
  magnitude above the eventual floor because C1's output carries
  profiling instrumentation and skips C2's aggressive optimizations.
- **Blocks 3-6** (~200-410 ns, settling): steady state under the first C2
  compilation — the `(C2)` entry at 1,889 ms explains the second, larger
  step-down; block 3 is still slightly elevated as the newly-compiled
  code's first invocations warm any remaining caches, and blocks 4-6
  settle to the run's floor.
- **Block 7** (5,650 ns): this is **not** a fresh warm-up cost — it is a
  **deoptimization spike**. The `MAKE-NOT-ENTRANT`/`UNCOMMON-TRAP` pair at
  ~6,104-6,110 ms falls in this block's time window: a speculative
  assumption C2 had compiled into `priceMono` (the log's `reason=
  unstable_if`, i.e. a branch C2 had speculated would resolve one way)
  stopped holding, the guard fired, the compiled method was discarded
  ("made not entrant"), and execution fell back to the interpreter for
  this call site until the 7,350 ms recompilation — landing block 7's
  cost back near interpreter-adjacent levels, not because the JVM is
  "warming up again" from scratch, but because compiled code was
  specifically invalidated.
- **Blocks 8-10** (~200-640 ns): the post-recompile steady state — block 8
  is still slightly elevated (freshly re-compiled code, same brief
  settling pattern as blocks 2-3), and blocks 9-10 return close to the
  original floor, consistent with the log's `note=guard widened` — the
  new compilation guards a less specific (more robust) assumption than
  the one that failed, so this call site is less likely to deopt again,
  though this single log entry cannot prove that guarantee in general.

</details>
