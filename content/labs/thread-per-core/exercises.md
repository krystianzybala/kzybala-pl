# Thread-per-core architecture — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (partitioned, and still not scaling)

A team partitions their event-processing state per thread — one thread per
core, no locks, each thread owns its partition's `long[]` statistics
slots, laid out as one big array:

```java
// N partitions, one owner thread each
long[] stats = new long[N_PARTITIONS * SLOTS_PER_PARTITION]; // SLOTS_PER_PARTITION = 3
void record(int partition, int slot) {
    stats[partition * SLOTS_PER_PARTITION + slot]++;
}
```

Throughput at 8 threads is barely 2× the single-thread number. A profiler
shows no lock contention (there are no locks) and near-100% CPU on all
eight cores.

**Task:** explain why "each thread only writes its own slots" is not the
same as "each thread owns its cache lines", quantify the problem for
`SLOTS_PER_PARTITION = 3`, and state two independent fixes.

**Success criteria:** your explanation names the exact mechanism (which
threads' slots share a 64-byte line for this layout), your quantification
says how many partitions collide per line, and your fixes preserve the
exact final counts.

<details>
<summary>Hint</summary>

3 slots × 8 bytes = 24 bytes per partition. How many partitions fit in one
64-byte line? Which lab on this site is this failure mode named after?

</details>

<details>
<summary>Solution</summary>

Logical ownership was partitioned; physical cache lines were not. At 24
bytes per partition, ~2.7 partitions — i.e. slots owned by *three
different threads* — share every 64-byte line, so every increment
invalidates two other cores' copies: textbook false sharing, invisible to
lock-oriented profiling and to CPU-utilization graphs (the cores are busy
— busy waiting on line transfers). Fixes: (1) pad/align each partition's
slots to a line boundary (space cost: 64 B per partition); (2) restructure
so each thread's hot slots live in its own thread-local block and are only
aggregated on read (ownership partitioning applied to the *layout*, not
just the index space). Both keep counts exact; verify with the lab's
correctness-test pattern and, on the dedicated Linux host, with the
false-sharing lab's evidence runner methodology.

</details>

## Exercise 2 — Implementation (make imbalance visible, then bound it)

The lab's benchmark drives all partitions with a uniform load — the
best case for partitioning.

**Task:** using the project in `code/`, add a skewed-load scenario: 80% of
operations target one partition (a "hot key"), the rest spread uniformly.
Then add the simplest mitigation that does not reintroduce sharing:
bounded queues + backpressure between the ingress and the partition
owners, so the hot partition's backlog is capped instead of unbounded.

**Success criteria (measure, don't assert):**

1. Correctness: total processed count equals total offered count minus
   explicitly rejected/shed operations — nothing silently lost.
2. Your skewed scenario shows aggregate throughput dropping toward the
   single-owner ceiling (the hot partition's owner saturates while others
   idle) — record the utilization spread across owners.
3. With bounded queues, ingress observes backpressure (measurable
   rejection/wait rate) instead of unbounded memory growth — record queue
   depth over time to show it is capped.
4. You can state what partitioning *cannot* fix here: one partition's
   ceiling is one core, and only re-partitioning the hot key's state
   (splitting it) raises it.

<details>
<summary>Hint</summary>

Measure per-owner processed counts, not just the aggregate — the
imbalance is the per-owner spread. For the bound, an
`ArrayBlockingQueue`/bounded SPSC per owner with an explicit
offer-timeout-reject policy is enough; the exercise is about making the
overload response *explicit*, not about queue implementation cleverness.

</details>
