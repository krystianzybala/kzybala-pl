# Deoptimization and uncommon traps — exercises

## Exercise 1 (diagnosis): the mystery p999 spike

A service's dashboard shows p50/p95/p99 latency as rock-stable for weeks.
One day, someone looks at p999 (not usually monitored) and finds a
recurring pattern: once roughly every million requests, a single request
takes 200× the p50 — always roughly the same magnitude, never clustering,
never correlated with load. Diagnose what class of cause this pattern is
most consistent with, and what you would check first, using this lab's
mechanism.

**Success criteria:** you name that a fixed-magnitude, non-clustering,
load-independent rare spike is far more consistent with a per-occurrence
event (a specific rare input finally hitting a code path, like this lab's
`rareExceptionPath`/`nullabilityShift`) than with a resource-contention
explanation (which would usually correlate with load or cluster in time);
you propose checking whether the spike's rate matches the rate of some
specific rare input condition in the request stream (a particular record
shape, a null field, an edge-case value) rather than assuming it is
infrastructure noise; and you state that JFR/`-XX:+PrintCompilation`
logs from a production JVM (or a reproduction harness like this lab's
`DeoptTimelineHarness`) are the direct way to confirm a deopt occurred at
the same moment, rather than inferring it from latency alone.

<details>
<summary>Hint</summary>

"Fixed magnitude, non-clustering, load-independent" rules out several
common explanations (GC pause scales with heap pressure and tends to
cluster; contention scales with concurrent load) — what's left that
produces a consistent, rare, one-time cost per occurrence?
</details>

## Exercise 2 (implementation): move the shift point and predict the batch

Using this lab's Java code, change `DeoptFixtures.SHIFT_POINT` to a new
value (e.g., 300,000) and rerun `DeoptTimelineHarness` for
`profileShiftAfterWarmup` (dev machine, wiring-only — do not publish
these numbers). Predict, before rerunning, which batch index should show
the spike.

**Success criteria:** your prediction (`newShiftPoint / BATCH_SIZE`)
matches where the spike actually appears in the reported `shiftWindow`;
you state explicitly that moving the shift point should NOT change the
final checksum (verify this — it is exactly this lab's correctness
invariant); and you revert `SHIFT_POINT` to `500_000` afterward — it is
not part of the lab's fixture contract, and every fixture-pinned total in
this lab's tests depends on the original value.

<details>
<summary>Hint</summary>

The harness reports `shiftWindow` starting at
`SHIFT_POINT / BATCH_SIZE` — if you move `SHIFT_POINT`, the *label* of
where to look moves with it; the mechanism (one spike right at the
transition) should not otherwise change.
</details>

<details>
<summary>Solution</summary>

Moving `SHIFT_POINT` only relabels *where* in the sequence the second
type first appears — it does not change what happens (a genuine deopt at
that point) or the final sum (permutation- and position-independent,
since every element still contributes the identical value regardless of
when the type switch happens around it). If your spike does not appear
near the predicted batch, suspect that the JIT had not yet compiled the
hot method by that point in the run (check with
`-XX:+PrintCompilation` — an assumption can only be violated once the
JIT has actually made it) rather than assuming the mechanism failed.
</details>

## Exercise 3 (evidence interpretation): read the timeline windows, not just the label

Below is the shape of one variant's `DeoptTimelineHarness` output
(illustrative structure, not real captured evidence):

```text
variant=rareExceptionPath dataset=parsingMixedRecords
  preShift:    { p50Ns: 210, p99Ns: 340, p999Ns: 890, maxNs: 1200 }
  shiftWindow: { maxNs: 950 }
  postShift:   { p50Ns: 215, p99Ns: 355, p999Ns: 2100000, maxNs: 2100000 }
```

Answer from this block alone: (a) does the unremarkable `shiftWindow.maxNs`
(950 ns, close to `preShift`'s own max) mean this variant's rare-exception
mechanism failed to reproduce; (b) what does `postShift.p999Ns` jumping
to `2,100,000` while `postShift.p50Ns` stays at `215` tell you about how
many samples in that window were actually affected; (c) name the one
harness-design fact (documented in java.md) that explains exactly why
this variant's spike shows up here and not in `shiftWindow`.

**Success criteria:** (a) no — this variant's trigger index
(`RARE_EXCEPTION_INDEX = 700,000`) is deliberately placed past
`shiftWindow`'s range (which brackets `SHIFT_POINT = 500,000`), so an
unremarkable `shiftWindow` is the *expected* result for this specific
variant, not a failure; (b) a p50 essentially unchanged from `preShift`
alongside a p999 that jumps by four orders of magnitude means the vast
majority of `postShift` batches are completely unaffected and only the
extreme tail (at most a handful of batches, likely exactly one) carries
the cost — consistent with a single rare event, not a sustained
slowdown; (c) java.md documents that `RARE_EXCEPTION_INDEX = 700,000`
sits outside `shiftWindow`'s fixed range
(`SHIFT_POINT` to `SHIFT_POINT + SHIFT_WINDOW_CALLS`), so this variant's
spike is captured by `postShift`'s own max/p999 by construction, not by
`shiftWindow` — a fact about the harness's window boundaries, not about
whether the underlying mechanism occurred.

<details>
<summary>Hint</summary>

Compare the two trigger constants directly: `SHIFT_POINT` vs.
`RARE_EXCEPTION_INDEX`. Is the second one inside or outside the window
the harness labels `shiftWindow`?
</details>
