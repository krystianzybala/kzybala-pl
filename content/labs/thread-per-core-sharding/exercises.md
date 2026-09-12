# Thread-per-core and shared-nothing sharding — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the sharded system that didn't get faster)

A colleague partitions a hot data structure into 4 shards, one owner
thread each, expecting roughly 4x throughput. They measure and see only
about 1.2x. All 4 owner threads report roughly equal, low CPU
utilization — none of them looks bottlenecked.

**Task:** using this lab's own single-writer-vs-mutex-shards benchmark
data as a reference point, name at least two distinct, non-skew
explanations for "sharded but barely faster, with no shard individually
saturated," and describe what evidence would distinguish between them.

**Success criteria:** your two explanations are genuinely different
mechanisms (not two phrasings of the same idea), and for each you name a
specific piece of evidence (already discussed in this lab or its
prerequisites) that would confirm or rule it out.

<details>
<summary>Hint</summary>

"No shard is saturated" rules out the skew explanation from this lab's
theory page — that would show up as one shard pegged. What else could
make 4 parallel workers barely outperform 1, without any of them looking
individually overloaded?

</details>

<details>
<summary>Solution</summary>

Two real candidates, neither being skew: (1) **routing overhead
dominates the actual work**, exactly this lab's own benchmark.md finding
— if each shard's per-item work is cheap, the cost of getting a request
to its owner (a queue send/receive) can be a large fraction of total
cost, and adding more shards just adds more of that same per-item routing
overhead rather than removing a bottleneck; evidence: compare the
single-writer variant's cost against the mutex-shards variant's cost on
the *same* per-item work — if single-writer is comparable or worse, routing
is the story, not contention. (2) **a shared upstream/downstream
resource outside the sharded structure itself** — e.g., all 4 owner
threads write their results to one shared log, one shared metrics
counter, or contend for a shared connection pool — the sharding
successfully removed contention on the structure being measured while
leaving an unmeasured shared resource as the real bottleneck; evidence:
profile (or, in this lab's terms, `perf c2c`) the whole request path, not
just the sharded structure, looking for a still-contended cache line or
lock somewhere else in the pipeline.

</details>

## Exercise 2 — Implementation (add a work-stealing rebalance instead of stop-the-world)

This lab's `RebalanceSimulationKernel`/`rebalance_simulation` module
fully drains and joins phase 1 before phase 2 begins — a stop-the-world
model, disclosed as a simplification in theory.md.

**Task:** implement a variant where, instead of a full drain, an
overloaded shard's owner thread can hand off a *specific key range* to
an idle shard's owner mid-run, while both shards continue processing
their remaining, non-migrated keys without pausing.

**Success criteria (measure, don't assert):**

1. Every key's final count is still exactly correct, including keys
   in flight at the moment of migration (no double-count, no drop).
2. You can state, precisely, what additional synchronization your
   design needed beyond the original single-writer model to make the
   migrated key range safe — and why the original model's "only one
   thread ever writes this key" invariant required that addition.
3. You measure and report your design's rebalance pause (if any
   remains) against this lab's stop-the-world `rebalanceCostNanos` —
   is it smaller, and by how much, for the same workload?

<details>
<summary>Hint</summary>

The single-writer invariant this lab relies on ("only the owning thread
ever writes a key's counter") is only true as long as ownership doesn't
change *while a message for that key might still be in flight* to the
old owner. What has to happen to messages already in the old owner's
inbox for the migrated keys before the new owner can safely start?

</details>

## Exercise 3 — Evidence interpretation (reading per-shard occupancy against a "balanced" claim)

Below is a **synthetic teaching example** — fabricated per-shard
throughput and queue-depth figures, constructed for this exercise, not
captured from any real run. It is educational material for practicing
evidence interpretation only: it is never used as measurement evidence,
never supports this lab's performance conclusions, and never enters a
comparison or maturity calculation. (This lab's real per-shard evidence
comes exclusively from the native-Linux evidence runner's profiler
capture; see benchmark.md.)

```
4 shards, single-writer mechanism, "uniform" dataset by generator design:

Shard   Requests processed   Avg queue depth   p99 enqueue-to-process latency
  0            2,003               1.2 ms                 4 ms
  1            1,998               1.1 ms                 4 ms
  2            2,001               1.3 ms                 4 ms
  3            8,012              41.7 ms                310 ms
```

A teammate, looking only at "requests processed," concludes: "shard 3
just got more traffic this run, that's normal variance, nothing to fix."

**Task:** using all three columns, evaluate whether "normal variance" is
a reasonable explanation, and state what the queue-depth and latency
columns specifically rule out or support that the requests-processed
column alone cannot.

**Success criteria:** you compute (or estimate) the ratio between
shard 3 and the others on requests processed and correctly characterize
it as far outside plausible random variance for a workload the generator
intended as uniform; you state that the queue-depth and latency columns
are *consistent with*, not just coincidentally alongside, a genuine hot-
key routing bug (e.g. a hashing/modulo error sending far more keys to
shard 3 than intended) rather than an innocuous processing-speed
difference.

<details>
<summary>Hint</summary>

If shard 3 were simply "a bit unlucky" in an intended-uniform workload,
its queue depth and latency would look like the others' — a *processing*
slowdown and a *routing* imbalance produce different signatures across
all three columns together, not just the first one.

</details>

<details>
<summary>Solution</summary>

Shard 3 processed roughly 4x as many requests as each of the other three
shards (8,012 vs. ~2,000) — for a dataset the generator intended as
uniform across 4 shards, that is not plausible sampling variance; it is
consistent with routing sending a specific subset of keys to shard 3 far
more often than the intended `key % 4` distribution would. The queue-
depth and latency columns confirm this is a *routing* problem, not
merely "shard 3 happened to get more work and is keeping up fine": if
shard 3 were simply processing 4x the volume at the same per-item speed,
its queue depth and p99 latency would scale roughly proportionally but
shard 3's owner would still be draining its inbox — instead, its queue
depth (41.7ms) and p99 latency (310ms) are both roughly 30-80x the other
shards', indicating shard 3's owner cannot keep pace at all and requests
are backing up, the signature of a shard that is receiving load its
single-writer design was never sized for. "Normal variance" would not
predict any of these three columns moving together this dramatically;
the combination points specifically at a routing defect (most likely: the
key-to-shard function isn't actually distributing keys the generator
believes it is) rather than an innocuous processing-speed story.

</details>
