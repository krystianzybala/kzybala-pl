# Branch prediction and data distribution — theory

## Performance question and hypothesis

**Question:** when does a simple conditional become more expensive than the
work guarded by it?

**Hypothesis:** unpredictable branches produce measurable front-end
stalls, while sorted or biased data allows hardware predictors and
JIT/compiler transformations to recover throughput.

**What would disprove it:** if a 50/50-random branch and a 90/10-biased
branch over the same operation cost the same per element, if sorting the
same multiset changed nothing, or if the branchless arithmetic variant
were never faster than the random-branch variant on any data shape, the
premise would be wrong. Each claim has a paired variant (biased vs random,
random vs sorted, branchy vs branchless on identical data), and every
comparison is anchored to a correctness oracle: **sorting and going
branchless must never change the result, only the time** — proven by a
shared fixture both language suites reproduce bit-exactly before any
timing is trusted.

## Learning objective

Connect data distribution to branch misses, distinguish a genuine
branchless win from a benchmark that merely does more unconditional work,
and read the perf counters (`branches`, `branch-misses`, IPC,
cycles/element) that separate the two.

## Prerequisites

- The [Benchmark harness traps](/lab/benchmark-harness-traps/) lab (DCE,
  warm-up and harness-settings discipline are assumed here).
- Basic CPU pipeline concepts: fetch/decode/execute stages, and that a CPU
  speculatively continues past a not-yet-resolved conditional branch.

## Pre-lab diagnostic

Two engineers benchmark the same filter-and-sum loop. One reports it takes
3 ms over 1,000,000 elements; the other, using the *same code* on the
*same value range*, reports 1 ms. Neither benchmark is broken. What is the
single most likely difference between their two input arrays?

(Answer at the end of this page.)

## The mechanism: prediction, misprediction and the alternative

- **Why a branch has a cost beyond the comparison itself.** Modern CPUs
  are deeply pipelined and execute speculatively: when the front end meets
  a conditional branch, it does not wait for the comparison to resolve —
  it *predicts* the outcome (taken or not-taken) and keeps fetching and
  executing down that path. If the prediction is right, the branch was
  effectively free. If it is wrong, every speculatively-executed
  instruction is discarded and the pipeline refills from the correct
  target — a **misprediction penalty** on the order of the pipeline's
  depth (commonly cited as ~15–20 cycles on contemporary x86-64 cores,
  host-specific and never assumed without measurement).
- **How the predictor decides.** A per-branch (or per-history-pattern)
  saturating counter is the textbook building block. A 2-bit counter has
  four states — strongly-not-taken, weakly-not-taken, weakly-taken,
  strongly-taken — nudged one step toward "taken" on a taken outcome and
  one step toward "not-taken" otherwise, and it predicts whichever
  direction it currently leans:

  ```text
  00 (strong NT) --taken--> 01 (weak NT) --taken--> 10 (weak T) --taken--> 11 (strong T)
       ^                        ^                        |                     |
       |<-----not-taken---------|<-----not-taken----------                     |
                                                    <--------not-taken----------|
  ```

  A run of identical outcomes saturates the counter and every further
  repeat is predicted correctly. A branch that flips almost every time
  drives the counter through its middle states, where it is wrong roughly
  as often as it is right. Real predictors add branch-target buffers and
  (local/global) history to catch patterns a single counter cannot, but
  the saturating-counter model already explains this lab's whole matrix.
- **What "data distribution" does to that mechanism.** The predictor does
  not see your data; it only sees the sequence of taken/not-taken outcomes
  your data produces through the comparison. A **90/10 biased** stream
  saturates the counter toward the majority outcome and mispredicts close
  to the minority's share. A **50/50 random** stream never lets the
  counter saturate — it is wrong close to half the time, independent of
  how simple the guarded work is. **Sorting** the exact same multiset does
  not change a single value or the filtered sum, only the *order* — after
  sorting, the predicate result flips at most a handful of times across a
  million elements, and the predictor saturates almost immediately.
- **The branchless alternative.** Replace the conditional with arithmetic
  that computes the same result unconditionally: derive a 0/1 (or
  all-zero/all-one) mask from the comparison without an `if`, `? :`, or
  any construct the compiler/JIT might turn back into a branch, and use
  the mask to select or zero the contribution. This lab's branchless
  variant computes `keep = ((value − threshold) >>> 31) ^ 1` — an
  arithmetic 0/1, never a boolean the compiler is free to branch on — and
  accumulates `value * keep`. There is no prediction to get wrong, but
  **every element now does the multiply-and-add unconditionally**, even
  the elements a branchy version would have skipped entirely. This is the
  lab's performance question made concrete: branchless removes the
  misprediction risk at the price of guaranteed extra work on the
  filtered-out elements — a win only when that guaranteed cost is smaller
  than the *expected* misprediction cost it replaces.
- **Compilers already do some of this for you — which is a trap, not a
  bonus.** A JIT or C2 will sometimes lower a simple `cond ? a : b` (or
  even a small `if` with no side effects) to a conditional move (`cmov`)
  instead of a branch, with no misprediction risk at all. If your
  "branchy" baseline accidentally gets cmov'd, you are no longer measuring
  what you think you are measuring — this lab's branchy variant uses a
  real `if` with a side-effecting accumulation specifically to keep the
  comparison honest, and the traps section below names the related
  failure mode explicitly.

## Visualization 1: the predictor state machine (conceptual model)

The four-state saturating counter from the mechanism section above, as a
lookup table (no branch is actually run to produce this — it is the
textbook model this lab's variants are built to exercise):

| Current state | Outcome: taken | Outcome: not-taken |
|---|---|---|
| 00 strongly not-taken | → 01 weakly not-taken | → 00 (saturated) |
| 01 weakly not-taken | → 10 weakly taken | → 00 strongly not-taken |
| 10 weakly taken | → 11 (saturated) | → 01 weakly not-taken |
| 11 strongly taken | → 11 (saturated) | → 10 weakly taken |

Prediction = "taken" in states 10/11, "not-taken" in states 00/01. A
misprediction only ever moves the counter one step; it takes two
consecutive wrong guesses to flip the prediction itself.

## Visualization 2: outcome probability vs expected misprediction rate (theoretical model)

An idealized model of how a *single* saturating counter behaves at
steady state under each of this lab's data arrangements — a conceptual
guide to what the real perf-counter evidence should qualitatively show,
**not a measurement**:

| Arrangement | Predicate-true share | Steady-state pattern seen by the predictor | Expected misprediction behavior |
|---|---|---|---|
| 90/10 biased | 90% | long runs of the majority outcome, occasional isolated flips | low — the counter saturates toward the majority and only the ~10% minority outcomes cost a flip |
| 50/50 random | 50% | no exploitable run length, outcome close to a coin flip | high — no run is long enough to saturate the counter before it flips again |
| sorted | 50% (same multiset as random) | one or a handful of transitions total across the whole pass | near zero after the first few elements — the counter saturates once per run and the run is nearly the whole array |
| branchless | n/a (no branch to predict) | — | zero, by construction — the guaranteed unconditional work replaces the prediction question entirely |

The real evidence panel (benchmark.md) reports `branches`, `branch-misses`
and IPC from `perf stat` on the native-Linux host, which is what turns
this theoretical row into a checked claim rather than an assumption.

## Visualization 3: branchy vs branchless, illustrative compiled pattern

A generic, textbook x86-64 lowering of the two techniques on a
`value >= threshold` filter — **illustrative of the general pattern only,
not extracted from a live run of this lab's code**; the real annotated
assembly for this benchmark comes from `-prof perfasm` (JMH) and
`cargo asm` on the native-Linux host and is linked from benchmark.md once
captured:

```text
; branchy (an unpredictable comparison compiles to a conditional jump)
    cmp   eax, threshold
    jl    .skip          ; front end must PREDICT here
    add   rsum, rax
.skip:

; branchless (the same comparison, no jump anywhere)
    sub   ecx, threshold, eax   ; diff = value - threshold
    shr   ecx, 31               ; 1 if diff negative (value < threshold), else 0
    xor   ecx, 1                ; keep = 1 if value >= threshold, else 0
    imul  ecx, eax               ; value * keep
    add   rsum, rcx
```

Textual fallback for both tables above and this listing: the predictor
model is a 4-state machine that predicts correctly whenever the same
outcome repeats and costs one flip's worth of mispredictions whenever it
changes; biased and sorted data produce few changes, random data produces
many, and removing the branch removes the question but not the work.

## Terminology

- **Speculative execution** — continuing past a not-yet-resolved branch
  using a prediction, discarding the work if the prediction was wrong.
- **Misprediction penalty** — the pipeline-refill cost paid when a
  prediction is wrong; host- and microarchitecture-specific.
- **Saturating counter** — a small per-branch state machine (commonly
  2-bit) that predicts whichever direction it currently leans.
- **Branch target buffer (BTB)** — a cache mapping branch addresses to
  predicted targets, so the front end knows *where* to speculate, not just
  *whether*.
- **Branchless / predication** — computing a result with unconditional
  arithmetic (a mask, `cmov`, or `select`) so there is no outcome to
  predict.
- **IPC (instructions per cycle)** — throughput evidence; a misprediction-
  heavy branchy loop and a branchless loop doing more total work can both
  depress IPC, for different mechanistic reasons — the counter evidence
  (branches/branch-misses vs instruction count) is what tells them apart.

## Assumptions and scope

- Dataset generation, sorting, and the correctness oracle all run before
  the timed region; the timed operation is exactly one pass over
  1,000,000 elements (benchmark.md, "operation definitions").
- The predictor model above describes a *single* saturating counter for
  intuition; production CPUs combine per-branch and history-based
  predictors and a BTB, and their exact behavior is proprietary and
  host-specific — this lab measures the aggregate effect (branches,
  branch-misses, ns/element), not any single predictor's internal state.
- Sorting and the branchless technique are proven equivalent to the
  branchy baseline by a shared correctness fixture (java.md, rust.md);
  the lab never asks you to trust that equivalence, only to verify it.

## Pre-lab diagnostic — answer

The most likely difference is the *order* of the input, not its range or
magnitude: a sorted (or otherwise runs-heavy) array lets the branch
predictor saturate almost immediately, while a randomly shuffled array
with the identical multiset of values keeps flipping the predictor's
guess. Same values, same filtered sum, same guarded work — different
predictability, different time. That is exactly this lab's sorted-vs-
random5050 pair, and it is why "what data did you use" is the first
question to ask of any branch-sensitive benchmark result.
