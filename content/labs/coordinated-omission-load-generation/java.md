# Coordinated Omission and Load Generation — Java

## Closed loop: the invariant made literal

```java
private static Outcome closedLoop(LoadParams params) {
    long clock = 0;
    for (int i = 0; i < params.count(); i++) {
        long serviceTime = params.serviceModel().serviceTimeNanos(i);
        long completion = clock + serviceTime;
        long responseTime = completion - clock; // == serviceTime, always
        // ...
        clock = completion;
    }
}
```

`responseTime` is computed as `completion - clock`, and `completion` is
defined as `clock + serviceTime` — so `responseTime` is *algebraically*
identical to `serviceTime` for every request. This is not an approximation
of closed-loop behavior; it is the literal structural property theory.md
describes, expressed as code the correctness suite can assert on directly
(`closedLoopResponseTimeAlwaysEqualsServiceTime`).

## Open loop: a FIFO admission queue keyed by completion time

```java
Deque<Long> inFlightCompletions = new ArrayDeque<>();
long nextFreeTime = 0;
for (int i = 0; i < params.count(); i++) {
    long sendTime = intendedSendTime.applyAsLong(i);
    while (!inFlightCompletions.isEmpty() && inFlightCompletions.peekFirst() <= sendTime) {
        inFlightCompletions.pollFirst();
    }
    if (inFlightCompletions.size() >= params.queueCapacity()) {
        missed++;
        continue;
    }
    long completion = Math.max(nextFreeTime, sendTime) + serviceTime;
    // ...
}
```

Because completions happen in the same order as admissions on a single
FIFO server, the number of requests "in flight" at any intended send time
equals the count of admitted completions still greater than that send
time — tracked here as a simple deque, popped from the front as requests
complete. This is the same bounded-admission shape as the
[Backpressure](/lab/backpressure-bounded-pipelines/) lab's `BOUNDED_REJECT`
policy, applied to a load generator's own send schedule.

## Omission correction: reconstructing the missing samples

```java
if (interval > 0 && responseTime > interval) {
    long missedIntervals = responseTime / interval - 1;
    for (long k = 1; k <= missedIntervals; k++) {
        long correctedSample = responseTime - k * interval;
        // record correctedSample as an additional sample
    }
}
```

When one real closed-loop sample's response time exceeds the target
interval, this computes how many requests *should* have been in flight
during that stall (`responseTime / interval - 1`) and synthesizes a
sample for each, with progressively shorter apparent latency as the
(hypothetical) queue would have drained — the standard Gil-Tene-style
correction, applied here to a fully deterministic, reproducible stall
rather than a live, noisy one.

## JMH benchmark

`CoLoadBenchmark` (`@Fork(1)`, 3 warmup + 5 measurement iterations of 1s
each, microsecond output) runs all five variants against
`periodicTenMsStall`, plus `OPEN_LOOP_FIXED_RATE` against
`boundedServerOverload` specifically to exercise its missed-schedule path.
Note: `poissonLikeArrivals`'s schedule function recomputes its cumulative
sum from scratch on every call (`O(n)` per lookup, `O(n²)` overall) — an
intentional simplicity trade-off for a 600-request dataset, disclosed in
benchmark.md rather than hidden, and irrelevant to correctness.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/coordinated-omission-load-generation/code/java" rel="noopener"><code>content/labs/coordinated-omission-load-generation/code/java/</code></a>
in this site's repository.
