# GC algorithms and tail latency — exercises

## Exercise 1 (diagnosis): the collector that "got slower"

A team migrates a service from Parallel to G1 expecting lower p99
pauses. Throughput drops slightly (expected — the trade-off this lab's
theory page names), but p99 pause is *unchanged* from before the
migration. A teammate concludes "G1 isn't actually better, the vendor
docs are wrong." Using this lab's mechanism, name the one measurement you
would take before accepting that conclusion, and what result would
actually support the teammate's claim versus point at a different cause
entirely.

**Success criteria:** you name live-set size and allocation rate as the
measurements to take (via this lab's `growingLiveSet`/
`steadyHighAllocation` distinction) rather than accepting the collector
flag alone as the explanation; you state that if the service's live set
is large and climbing (this lab's `growingLiveSet` pattern), G1's pause
target is fighting a workload shape it cannot help with regardless of
collector choice, which would NOT support "G1 isn't better" so much as
"this workload's live-set growth dominates over collector strategy"; and
you state that only a live set that is small and stable, with p99 still
unchanged after migration, would actually support the teammate's
conclusion.

<details>
<summary>Hint</summary>

This lab's `collectorMatrix` variant only shows a real collector-strategy
difference because live set and allocation pattern are held byte-for-byte
identical between the two runs. If the teammate's before/after workload
isn't actually identical, what else could explain "no improvement"?
</details>

## Exercise 2 (implementation): find where reuse stops paying off

Using this lab's Java `GcTailOperations`, add a new variant
`partialReuse(values, extraSize, reuseFraction)` that reuses a `Node` for
`reuseFraction` of iterations and allocates fresh for the rest (unlike
`burstyAllocation`'s fixed 100/20 block pattern, distribute the fresh
allocations evenly — e.g., every `1/(1-reuseFraction)`th iteration).
Wire it into `GcTailHarness` and run it at `reuseFraction = 0.5, 0.9,
0.99` on `objectGraphChurn` (dev machine, wiring-only — do not publish
these numbers). Predict, before running, whether GC episode count scales
roughly linearly with `(1 - reuseFraction)`.

**Success criteria:** you implement the variant correctly (verify its
checksum still matches the fixture-pinned total — reuse fraction must
never change the result, only allocation pressure); you state a specific,
falsifiable prediction about episode count scaling; you measure and
report the actual result; and you connect it back to this lab's
mechanism — allocation rate determines collection *frequency*, so a
roughly linear relationship between fresh-allocation fraction and episode
count is the expected shape, while a live set that stays flat regardless
of `reuseFraction` (since nothing is retained) should keep each
individual episode's pause roughly comparable to `steadyHighAllocation`'s.

<details>
<summary>Hint</summary>

`lowAllocationReuse` (100% reuse) triggered zero episodes;
`steadyHighAllocation` (0% reuse) triggered several on the same dataset
size. Where would you expect a variant with, say, 90% reuse to land
between those two endpoints?
</details>

<details>
<summary>Solution</summary>

Episode count should scale roughly with the fraction of iterations that
actually allocate, since that fraction determines how fast the young
generation fills. `reuseFraction = 0.99` should land close to
`lowAllocationReuse`'s near-zero episode count; `reuseFraction = 0.5`
should land noticeably higher, though not necessarily exactly half of
`steadyHighAllocation`'s count, since collection triggering depends on
region/generation fill thresholds, not a purely linear counter. If your
measured episode counts do not scale linearly, that is itself a valid,
useful finding — it tells you the collector's triggering heuristic is not
a simple linear function of allocation fraction, worth stating explicitly
rather than assuming linearity because the mechanism sounds like it
should be.
</details>

## Exercise 3 (evidence interpretation): read the episode, not the average

Below is the shape of one run's captured episode data from this lab's
publication runner (illustrative structure, not real captured evidence):

```text
variant=burstyAllocation dataset=objectGraphChurn
  episode 1: gcId=1 durationNanos=980000   heapUsedAfter=11800000
  episode 2: gcId=2 durationNanos=1040000  heapUsedAfter=12100000
  episode 3: gcId=3 durationNanos=6200000  heapUsedAfter=12050000
```

Answer from this block alone: (a) does episode 3's much larger
`durationNanos` (6.2 ms vs. ~1 ms for the other two) mean the collector
itself became slower over the run, given that `heapUsedAfter` stayed
roughly flat across all three episodes; (b) `heapUsedAfter` is nearly
identical across all three episodes — does that rule out live-set growth
as episode 3's cause; (c) name one alternative, mundane explanation
consistent with `burstyAllocation`'s own design (theory.md) for a single
elevated pause among otherwise-consistent episodes.

**Success criteria:** (a) not necessarily — a roughly flat
`heapUsedAfter` across all three episodes argues *against* a growing live
set as the explanation, since live-set growth is exactly what this lab's
`growingLiveSet` variant isolates and this data does not show it; (b)
yes — flat `heapUsedAfter` specifically rules out live-set growth as
episode 3's cause, since the amount surviving each collection did not
change; (c) `burstyAllocation`'s own design is the mundane explanation
this lab predicts directly: the burst blocks allocate an 8× larger
payload than the quiet blocks, so a collection landing mid-burst (or
triggered by a burst's larger allocations) can cost more even with a
comparable post-collection live set, simply because more bytes were
scanned/copied during that particular collection — exactly the
"workload phase changes which percentile matters" point from theory.md,
not a sign the collector degraded.

<details>
<summary>Hint</summary>

This lab's theory page states explicitly that a bursty allocation
pattern can make a collection landing mid-burst look different from one
landing during a quiet stretch, even with a comparable live set
afterward. What differs about a burst block's allocations specifically?
</details>
