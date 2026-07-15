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
