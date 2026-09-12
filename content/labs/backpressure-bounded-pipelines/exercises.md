# Backpressure and Bounded Pipelines — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the metric that hid the real problem)

A team runs `boundedReject` in production and reports "median latency is
great, 4ms, no problem." Under a sustained overload period, their actual
customer complaints spike — requests are failing. Their dashboard's median
latency never moved.

**Task:** explain why median (p50) latency stayed flat while real failures
spiked, and name which metric from this lab's required set would have
caught the problem immediately.

**Success criteria:** you explain that `boundedReject`'s *accepted*
requests still complete quickly (they were never queued behind a backlog —
they either got in immediately or were rejected immediately), so their
latency distribution is unaffected by overload; the failures are hiding in
the **rejected count**, a completely separate metric from latency of
accepted requests. You correctly identify "drop/reject rate" as the metric
that would have shown the problem on the first overloaded request.

<details>
<summary>Hint</summary>

Look at what `boundedReject` actually does to a request once the queue is
full: does that request experience *any* latency at all, or does it never
enter the latency-measuring path in the first place?

</details>

<details>
<summary>Solution</summary>

`boundedReject`'s core property is that an accepted item never waits
behind a backlog — it either gets a free slot immediately or it is
rejected immediately, with nothing in between. This means the *latency
distribution of accepted requests* is nearly independent of overload: the
requests that do get in are exactly as fast whether the system is at 10%
or 99% capacity, because rejection removes the excess before it can queue
and add latency. The cost of overload under this policy shows up entirely
in the **rejected count**, not in the latency of successful requests —
which is precisely why "reporting only successful requests" is listed as
a known trap in theory.md: a dashboard that tracks only accepted-request
latency for a `boundedReject` system will report a perfectly flat, healthy
number straight through a period where the majority of requests are being
turned away. The fix is not a different policy — `boundedReject` may be
the right choice — it is exposing drop/reject rate as a first-class metric
alongside latency, not as an afterthought.

</details>

## Exercise 2 — Implementation (a policy that adapts its own threshold)

`LOAD_SHEDDING` in this lab uses one fixed occupancy threshold
(`shedThresholdPercent`) for every dataset.

**Task:** implement an adaptive variant that raises its shedding threshold
during a sustained-low-arrival-rate period and lowers it during a
sustained-high-arrival-rate period (a simple exponential moving average of
recent per-tick arrivals is enough), while preserving this lab's
correctness invariant (`produced == delivered + rejected + dropped +
superseded`) exactly.

**Success criteria (measure, don't assert):**

1. The existing correctness invariant still holds for every dataset with
   your adaptive variant.
2. On `sustainedOverload`, your adaptive variant's total `dropped` count is
   measurably different (lower or higher, and you can explain which and
   why) from the fixed-threshold `LOAD_SHEDDING` baseline.
3. You can explain one scenario where an adaptive threshold is *worse*
   than a fixed one — for instance, an average that reacts too slowly to a
   sudden burst, briefly admitting a burst it should have shed sooner.

<details>
<summary>Hint</summary>

Keep the moving average and the threshold-adjustment logic entirely
outside the item-by-item admission decision — compute it once per tick,
before the per-tick production loop, exactly the way `maxDepth` is
recomputed once per tick rather than per item.

</details>

## Exercise 3 — Evidence interpretation (a recovery-time chart)

Below is a **synthetic teaching example** — constructed for this exercise,
not captured from any run — modeled on the shape a real queue-depth
timeline takes across an overload-then-recovery window, the visualization
this lab's design.md calls the "overload phase plot." It is educational
material for practicing evidence interpretation only: it is never used as
measurement evidence, never supports this lab's performance conclusions,
and never enters a comparison or maturity calculation.

```
Time (s)   Queue depth, Policy A   Queue depth, Policy B
0          0                       0
1          8 (burst starts)        8
5          8                       8
6          8 (burst ends)          8
7          6                       8
8          4                       8
9          2                       8
10         0                       7
11         0                       6
...
20         0                       0
```

Both policies have the same capacity (8) and hit it during the same burst.
**Task:** name the most likely policy difference between A and B that
would produce this pair of recovery curves, and state one conclusion this
chart **cannot** support on its own.

**Success criteria:** you correctly reason that Policy A's queue depth
tracks its *actual pending items* and drains normally once the burst
ends, consistent with a reject/drop-style policy where nothing beyond
capacity was ever queued to begin with; Policy B's queue stays pinned at
capacity for several seconds after the burst ends before draining, which
is the signature of `boundedBlock`'s backlog — items that couldn't fit
during the burst are still waiting their turn well after new production
has stopped. Your "cannot conclude" statement recognizes that queue-depth
alone does not reveal *how many* items each policy actually delivered
successfully, or what each policy's total drop/reject count was during the
burst — those require the drop/reject-rate metric this chart does not
show.

<details>
<summary>Solution</summary>

Policy B's queue staying at capacity (8) for several ticks after
production has already stopped is the tell: something is still being
inserted into the queue from a backlog that formed *during* the burst,
after the burst window in the production schedule has already ended —
exactly `boundedBlock`'s behavior, where excess production during overload
is deferred into a backlog and drained item-by-item once room frees up,
rather than rejected or dropped immediately. Policy A's queue depth
returns to normal the moment the burst ends, consistent with a policy that
never accumulates a backlog beyond its live queue (`boundedReject`,
`dropOldest`, or `loadShedding` would all show this shape). **What this
chart cannot support:** which specific policy A is (all three
non-backlog policies produce a similar depth curve, distinguishable only
by their drop/reject/superseded counts, not by queue depth alone), or how
many total items each policy successfully delivered during the overload
window — a flat, low queue-depth curve for Policy A is consistent with
"handled overload gracefully" and equally consistent with "rejected nearly
everything," and this chart alone cannot tell you which.

</details>
