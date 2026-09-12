# SPSC ring buffer — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the off-by-one that only fails under load)

A colleague implements an SPSC ring and reports that a stress test
occasionally consumes a *stale or half-written* item — but only at high
rates, never in unit tests. Their producer:

```java
buffer[(int) (head % capacity)] = item;   // 1. write payload
HEAD.setRelease(this, head + 1);          // 2. publish
```

and their consumer:

```java
long h = (long) HEAD.get(this);           // plain read
if (tail < h) {
    T item = buffer[(int) (tail % capacity)];
    TAIL.setRelease(this, tail + 1);
    return item;
}
```

**Task:** find the bug. It is not in the producer.

**Success criteria:** you can name the exact pair of accesses whose
ordering is unconstrained, explain why unit tests (single-threaded or
low-rate) never catch it, and state the one-token fix.

<details>
<summary>Hint</summary>

The producer's `setRelease` is only half of a publication handshake. What
does the *consumer* have to do for the release to mean anything — and
what did this consumer do instead?

</details>

<details>
<summary>Solution</summary>

The consumer reads `HEAD` with a **plain** `get`, not `getAcquire`. A
release store only orders the payload write *before* the cursor publish;
the consumer needs the matching acquire load to order the cursor read
*before* the payload read. With a plain read, the consumer's payload load
may be satisfied before it observes the new head value's implications —
compiler and CPU are both free to reorder — so it can read the slot before
the producer's payload write is visible. Single-threaded tests can't fail
(no concurrent visibility at all), and at low rates the window between
publish and consume is huge compared to any reordering window; only under
sustained load does the consumer race the producer closely enough to
observe it. Fix: `HEAD.getAcquire(this)`. (Rust: `load(Acquire)` matching
the `store(Release)`.) This is exactly the "publication" half of the
memory-ordering lab applied to a cursor.

</details>

## Exercise 2 — Implementation (batch the consumer, measure the cursor traffic)

The lab's implementations (`code/java/`, `code/rust/`) consume one item
per acquire-load of the producer's cursor.

**Task:** implement drain-in-batches consumption: read the producer cursor
once, consume *everything* available up to it, then publish one tail
update for the whole batch.

**Success criteria (measure, don't assert):**

1. The existing correctness tests still pass (every item delivered exactly
   once, in order — including across wrap-around).
2. On a two-thread benchmark modeled on the lab's harness, items-per-second
   improves measurably over the per-item version on your machine at high
   rates.
3. You can explain *which* cross-core accesses your change eliminated
   (cursor reads by the consumer, cursor-line invalidations at the
   producer) — and why the win shrinks when the buffer is nearly empty
   (batches degenerate to size 1, so the traffic returns).

<details>
<summary>Hint</summary>

Correctness first: the tail you publish after a batch must be exactly
`oldTail + consumed`. The trap is the empty-check inside the batch loop —
you already know how many items are available from the single head read;
don't re-check the head per item or you reintroduce the traffic you're
trying to remove.

</details>

## Exercise 3 — Evidence interpretation (coordinated omission in a latency report)

Below is a **synthetic teaching example** in HdrHistogram's percentile-table
format — constructed for this exercise, not captured from any run. It is
educational material for practicing latency-report interpretation only: it
is never used as measurement evidence, never supports this lab's
performance conclusions, and never enters a comparison or maturity
calculation. (The lab's real one-way latency evidence comes exclusively
from the native-Linux evidence runner and is imported with full
provenance; see `benchmark.md`.) Two harnesses measured the *same*
producer/consumer pair under the *same* steady send rate, one-way latency
in microseconds, labels removed:

```
Report A                          Report B
50.000%     8 us                 50.000%     9 us
90.000%    12 us                 90.000%    14 us
99.000%    15 us                 99.000%    41 us
99.900%    19 us                 99.900%   812 us
99.990%    22 us                 99.990%  4,930 us
99.999%    26 us                 99.999%  9,105 us
Max        31 us                 Max      11,220 us
Count  10,000,000                Count  10,000,000
```

Both harnesses ran the identical ring-buffer implementation, at the
identical send rate, for the identical duration. One harness times each
message from the instant it *should* have been sent (its scheduled send
time, given the fixed rate), even if the producer was blocked waiting for
a free slot; the other times each message only from the instant it was
*actually* sent.

**Task:** decide which report (A or B) is the one still contaminated by
coordinated omission, and justify it from the shape of the two
distributions — not just from the fact that the numbers differ. Then state
one conclusion this data **cannot** support.

**Success criteria:** your identification is correct, your reasoning
names what coordinated omission specifically hides (stalls counted as "no
sample" instead of "a very late sample"), and your "cannot conclude"
statement is genuinely unsupported by this data rather than merely
cautious.

<details>
<summary>Hint</summary>

Report A's tail barely grows from p50 to Max — every percentile stays
within roughly 4x of the median. Ask what has to be true of the
underlying system for a tail that flat: either the system truly never
stalls, or the measurement method is discarding exactly the samples that
would have shown a stall.

</details>

<details>
<summary>Solution</summary>

**Report A is the one still contaminated by coordinated omission.**

- A real bounded SPSC ring under steady load, sharing the machine with an
  OS scheduler, GC pauses (Java) or even brief page faults, will
  occasionally stall the producer or consumer for a duration far longer
  than its typical service time — that stall has to show up *somewhere*
  in a distribution of 10 million samples. Report A's tail (p99.999 of
  26 us, Max of 31 us — under 4x the median) is too flat to be honest
  evidence of that; report B's tail (Max of 11,220 us, roughly 1,400x the
  median) is the shape a real stall produces.
- The mechanism: a harness that only starts the clock when a message is
  *actually* sent skips the wait entirely when the producer is blocked on
  a full buffer — the message that should have been sent during the stall
  is simply sent late, timed as if it were on-schedule the moment it
  finally goes out. The stall itself disappears from the sample set
  instead of appearing as one very slow sample. This is exactly the
  "ignoring shutdown and wraparound"-adjacent trap this lab names
  separately as coordinated omission: the harness silently drops the
  evidence that would have told you queuing existed at all.
- Report B's harness times from the scheduled send instant, so a producer
  stall is captured as one (or several) message(s) whose recorded latency
  correctly includes the time spent waiting — which is why its tail
  actually reflects the stall.

**What this data cannot support:** any claim about *how often* stalls of
this size occur in production, or that this specific ring-buffer
implementation is unusually stall-prone — this is one synthetic pair of
distributions illustrating a measurement artifact, not a captured
duration, arrival-rate model, or host. It also cannot be used to compare
this lab's Java and Rust implementations against each other; that
comparison requires the real, provenance-tracked evidence in
`benchmark.md`.

</details>
