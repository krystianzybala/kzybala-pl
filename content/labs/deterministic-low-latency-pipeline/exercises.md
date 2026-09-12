# Deterministic Low-Latency Pipeline Capstone — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (four green benchmarks, one red production incident)

A team's four individual mechanism benchmarks — bounded queue, sharding,
zero-copy decode, and a load generator — all pass their own labs'
success criteria in isolation. They assemble all four into one pipeline,
ship it, and it misses its p99 target in production under real traffic,
specifically during trending-topic spikes where one key dominates.

**Task:** name the specific combination of this lab's mechanisms that
explains the incident, and which of this lab's four datasets would have
caught it before shipping.

**Success criteria:** you identify hot-key skew interacting with
per-shard (not global) capacity as the mechanism — a trending key
concentrates load onto one shard, and that shard's bounded capacity is
sized for an even, per-shard share of aggregate load, not for one shard
absorbing a large fraction of total traffic. You correctly name
`mediumEventsHotKeyBurst` (or an equivalent hot-key + burst dataset) as
the one that would have surfaced this, since `mediumEventsUniformBurst`
by construction cannot: uniform keys never concentrate.

<details>
<summary>Hint</summary>

Look at this lab's `hotKeySkewCanOverflowOneShardEvenWithGenerousAggregateCapacity`
test — what does "generous aggregate capacity" actually mean when 80% of
traffic lands on one of four shards?

</details>

<details>
<summary>Solution</summary>

Each of the four individual-mechanism benchmarks was tested against its
own lab's dataset, which — reasonably, for isolating that one mechanism —
used a uniform or otherwise well-behaved key distribution. Sharding's own
benchmark showed it distributes load evenly under uniform keys; the
bounded-queue benchmark showed capacity holds under its own tested load
shape. Neither individually-passing benchmark exercised the *combination*
where a skewed real-world key distribution meets per-shard (not global)
capacity: if capacity is set assuming each of 4 shards handles ~25% of
traffic, but a trending key routes 80% of traffic to one shard, that one
shard experiences roughly 3.2x its designed load while the other three
sit mostly idle — the aggregate capacity (4× per-shard capacity) was
never the binding constraint; one shard's *own* capacity was. This is
exactly what `mediumEventsHotKeyBurst` is built to exercise (this lab's
`hotKeySkewCanOverflowOneShardEvenWithGenerousAggregateCapacity` test
asserts `maxShardDepth > 0` specifically under that dataset), and exactly
why theory.md's pre-lab diagnostic makes this point about emergent
combination effects a named lesson.

</details>

## Exercise 2 — Implementation (a rebalancing overload policy)

The `OPTIMIZED` pipeline's admission policy treats each shard's capacity
as fixed and independent — a hot shard drops even when a cold sibling
shard has spare capacity.

**Task:** implement a variant where a rejected admission first checks
whether *any* shard has spare capacity and, if so, routes the event
there instead of dropping it (breaking strict single-writer-per-key
ownership for overflow events only), while preserving this lab's
correctness invariant exactly.

**Success criteria (measure, don't assert):**

1. `produced == delivered + dropped + restartLoss` still holds exactly.
2. On `mediumEventsHotKeyBurst`, your rebalancing variant's `dropped`
   count is measurably lower than the plain `OPTIMIZED` variant's.
3. You can explain one correctness/ordering cost of this change: what
   guarantee does routing an overflow event to a *different* shard than
   its key normally maps to give up, and why might that matter for a
   real decision pipeline (hint: think about what "single-writer shard"
   was providing in the first place).

<details>
<summary>Hint</summary>

The naive approach is: on rejection, scan all shards for one with
`queue.size() < capacity` and admit there instead. What per-key ordering
guarantee does this immediately break, compared to strict `key %
numShards` routing?

</details>

## Exercise 3 — Evidence interpretation (a latency budget waterfall)

Below is a **synthetic teaching example** — constructed for this
exercise, not captured from any run — modeled on the shape a real
end-to-end latency budget breakdown takes for one event through this
lab's pipeline stages. It is educational material for practicing evidence
interpretation only: it is never used as measurement evidence, never
supports this lab's performance conclusions, and never enters a
comparison or maturity calculation.

```
Stage                    p50      p99      p999
ingest -> decode          80ns    120ns     340ns
route -> admit            40ns     60ns   4,200ns
shard queue wait          10ns  1,800ns  48,000ns
decide (decision fn)     150ns    210ns     480ns
--------------------------------------------------
end-to-end               280ns  2,190ns  53,020ns
```

**Task:** identify which single stage is responsible for almost the
entire p999 latency, and state one conclusion this table **cannot**
support about the pipeline's overall correctness or capacity.

**Success criteria:** you correctly identify "shard queue wait" as
responsible (48,000ns of the 53,020ns total p999 — roughly 90%), name
the mechanism (queueing delay under load concentrates in the tail even
when median/p50 looks fine, exactly this lab's and the
[Coordinated Omission](/lab/coordinated-omission-load-generation/) lab's
point about where overload actually shows up), and your "cannot support"
statement recognizes this breakdown says nothing about *which* dataset or
key pattern produced it, or whether the events represented in the p999
tail were later dropped, delivered, or lost to a restart — a latency
number alone doesn't carry admission outcome.

<details>
<summary>Solution</summary>

**"Shard queue wait" dominates p999** (48,000ns of 53,020ns total, ~90%),
while contributing almost nothing at p50 (10ns) — the classic queueing
signature: under light load a shard's queue is essentially always empty,
so wait time is negligible; the moment a shard is overloaded (a hot key,
a burst), queueing delay grows sharply and disproportionately affects the
tail, exactly matching this lab's and the
[Coordinated Omission](/lab/coordinated-omission-load-generation/) lab's
shared point that overload shows up in tail percentiles, not the median.
"Route -> admit" also grows meaningfully at p999 (4,200ns vs. 60ns at
p99) — consistent with contention on the admission check itself under
the same overload window. **What this table cannot support:** whether
those p999-tail events were ultimately delivered, dropped, or lost to a
shard restart — a stage-latency breakdown measures time-to-complete for
events that *did* complete each stage, and says nothing on its own about
admission outcome, which requires this lab's separate `dropped`/
`restartLoss` counters, not the latency table.

</details>
