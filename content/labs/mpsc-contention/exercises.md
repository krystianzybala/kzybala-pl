# MPSC queues and producer contention — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the fairness bug aggregate throughput hides)

A colleague benchmarks the single shared MPSC variant with 4 producers
and reports "8,000 items/ms aggregate, looks great." They do not report
anything per-producer. You suspect one producer might be starving the
others under contention.

**Task:** without changing the implementation, explain what evidence
from this lab's existing harnesses would let you confirm or rule out
producer starvation, and what a starved run would look like in that
evidence — as opposed to a healthy, evenly-shared run.

**Success criteria:** you name the specific field/data structure (not
just "check fairness") that already exists in `MpscResult`/`MpscResult`
(Rust) capable of answering this, and you describe the exact pattern in
per-producer counts that would indicate starvation versus the pattern
that would indicate healthy sharing.

<details>
<summary>Hint</summary>

The correctness oracle already decodes every received item back to its
producer id. What would it take to turn "assert every producer got
exactly `itemsPerProducer`" into "report how *unevenly in time* one
producer's items arrived relative to another's"?

</details>

<details>
<summary>Solution</summary>

The correctness check already proves every producer eventually gets all
`itemsPerProducer` items through — by construction, this lab's producers
run to completion regardless of fairness, so total-count correctness
alone cannot reveal starvation. What's needed is a *temporal* view: for
each received item, record its position in the consumer's overall arrival
order (not just decode producer/seq), then plot each producer's arrival
positions. A healthy, evenly-shared run shows all producers' arrivals
interleaved roughly uniformly throughout the whole sequence. A starved
run shows one producer's items clustered late — e.g. producer 3's 2,000
items mostly arriving only after producers 0-2 have already finished —
which aggregate throughput and a simple per-producer *count* check both
completely hide, since the starved producer still eventually finishes and
still contributes its exact expected count.

</details>

## Exercise 2 — Implementation (add a sharded MPSC: 2 shared rings instead of 1 or N)

This lab's two shared-structure variants sit at two extremes: one shared
ring (maximum claim-point contention) or N per-producer rings (zero
shared contention, but N structures for the consumer to poll).

**Task:** implement a middle ground: `K` shared rings (K < producer
count), where each producer is assigned to exactly one ring (e.g.
`producerId % K`), using the same claim/publish mechanism as
`SharedMpscKernel`/`shared_mpsc`. The consumer round-robins across the
`K` rings.

**Success criteria (measure, don't assert):**

1. Correctness holds for `K` values that don't evenly divide the
   producer count (e.g. 4 producers, `K = 3`) — every producer's items
   still arrive in exact per-producer FIFO order with the right total.
2. On a multi-producer benchmark run on your machine, throughput at
   `K = 2` (with 4 producers) falls somewhere between the `K = 1`
   (single shared MPSC) and per-producer fan-in (`K = producerCount`)
   numbers — report the three numbers side by side.
3. You can explain, from CAS-failure counts (or their absence), why `K`
   shared rings reduces contention roughly by a factor of `K` compared to
   one shared ring, and why it never reaches per-producer fan-in's zero
   contention unless `K` equals the producer count.

<details>
<summary>Hint</summary>

Two producers assigned to the same ring still contend with each other on
that ring's claim point — sharding reduces the *size* of each contended
group, it doesn't eliminate contention within a group.

</details>

## Exercise 3 — Evidence interpretation (reading CAS-failure counts against a scaling claim)

Below is a **synthetic teaching example** — fabricated CAS-failure and
throughput figures, constructed for this exercise, not captured from any
real run. It is educational material for practicing evidence
interpretation only: it is never used as measurement evidence, never
supports this lab's performance conclusions, and never enters a
comparison or maturity calculation. (This lab's real contention evidence
comes exclusively from the native-Linux evidence runner's `perf stat`/
`perf c2c` capture; see benchmark.md.)

```
Single shared MPSC, items/producer = 2,000, capacity = 1,024:

Producers   Aggregate items/ms   Total CAS attempts   Failed CAS attempts
    2              9,400               8,120                 120
    4              7,100              17,830               9,830
    8              4,050              41,900              33,900
```

A teammate concludes: "throughput dropped from 2 to 8 producers, so the
CAS retry loop is the bottleneck — we should switch to batched claims
immediately."

**Task:** using only this table, evaluate whether the data supports
"the CAS retry loop is *the* bottleneck," and state what additional
evidence (already discussed in this lab) would be needed to confirm
switching to batched claims is the right fix specifically, as opposed to
per-producer fan-in or sharding.

**Success criteria:** your evaluation identifies what the table *does*
support (contention is growing sharply with producer count — failed CAS
attempts go from ~1.5% to ~55% of total attempts) and what it does
*not* support on its own (that batched claims, specifically, is the best
fix among this lab's several contention-reducing designs); you name at
least one additional measurement that would distinguish between
candidate fixes.

<details>
<summary>Hint</summary>

The table only measures one design (single shared MPSC). Does it contain
any information at all about how batched claims, sharding, or
per-producer fan-in would perform on the *same* workload?

</details>

<details>
<summary>Solution</summary>

**What the table supports:** contention on the shared claim point is
real and growing non-linearly with producer count — the failed-CAS
fraction goes from roughly 1.5% at 2 producers to roughly 55% at 8, while
aggregate throughput falls by more than half over the same range. This is
consistent with (though not, by itself, proof of) the shared claim point
being a significant contributor to the throughput drop.

**What it does not support:** that batched claims is *the* correct fix,
or even a better fix than per-producer fan-in or sharding, because the
table contains no measurement of any variant *other than* the single
shared MPSC. A CAS-failure count tells you contention exists on this
design; it says nothing about how much of that contention a different
design would actually remove, because different designs remove
different amounts (batched claims reduces claim-point contention
frequency but keeps a shared structure and its bounded-capacity
backpressure; per-producer fan-in removes claim-point contention
entirely but adds consumer-side polling cost across N structures;
sharding is in between). The only way to know which fix helps most *for
this workload* is to measure the alternatives under the same conditions
— exactly this lab's own benchmark matrix, and exactly why "switch to
batched claims immediately" from this table alone would be an unproven
inference dressed as a measured conclusion.

</details>
