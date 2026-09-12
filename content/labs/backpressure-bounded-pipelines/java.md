# Backpressure and Bounded Pipelines — Java

## One simulator, six policies

Every variant shares one deterministic, single-threaded tick loop
(`PipelineSimulator.simulate`) — no real threads, no wall-clock timing —
so the correctness fixture can assert exact counts rather than
tolerances:

```java
public static SimResult simulate(Policy policy, PipelineParams params) {
    Deque<Integer> queue = new ArrayDeque<>();
    // ...
    for (int producedThisTick : params.producedPerTick()) {
        for (int i = 0; i < producedThisTick; i++) {
            switch (policy) {
                case UNBOUNDED -> queue.addLast(id);
                case BOUNDED_REJECT -> { /* offer or reject */ }
                case BOUNDED_BLOCK -> { /* offer or backlog */ }
                case DROP_OLDEST -> { /* evict head, then add */ }
                case COALESCE_BY_KEY -> { /* overwrite pending value */ }
                case LOAD_SHEDDING -> { /* proactive occupancy-based drop */ }
            }
        }
        // consumer drains up to consumeRate items this tick
    }
}
```

Each policy is expressed as one `switch` arm operating on plain
`java.util.Deque`/`Map` structures — no custom queue implementation is
needed to teach the policy layer, since the mechanism this lab is about is
*what happens when the structure is full*, not the structure itself (see
the [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab for the ring mechanics).

## Coalesce-by-key with `LinkedHashMap`

```java
Map<Integer, Integer> coalesceMap = new LinkedHashMap<>();
// ...
Integer previous = coalesceMap.put(key, id);
if (previous != null) {
    superseded++;
}
```

`LinkedHashMap`'s default (insertion-order) iteration is exactly the
semantics this policy needs: re-`put`ting an existing key overwrites its
value **without** moving it to a new position, so the delivery order among
currently-pending keys reflects each key's *first* arrival — a key that
gets updated five times while queued is still delivered in the position
its first update claimed, with the fifth (latest) value.

## Bounded block: a backlog, not a real park

```java
if (policy == Policy.BOUNDED_BLOCK) {
    while (!blockBacklog.isEmpty() && queue.size() < params.capacity()) {
        queue.addLast(blockBacklog.pollFirst());
    }
}
```

Because the correctness fixture is a deterministic simulation with no real
threads, "the producer blocks until there's room" is modeled as: anything
that didn't fit this tick waits in a backlog and is retried, in order,
before any of the *next* tick's production — preserving overall FIFO order
and guaranteeing zero drops, at the cost of increased sojourn time. The
real, threaded version of this policy (a bounded `BlockingQueue.put`) is
exercised by the JMH benchmark, not by this deterministic model.

## JMH benchmark

`BpBenchmark` (`@Fork(1)`, 3 warmup + 5 measurement iterations of 1s each,
`Mode.AverageTime`, microsecond output) measures the simulator's own
per-tick administrative cost for each policy against the
`sustainedOverload` dataset (`hotKeySkew` for `coalesceByKey`, since that
dataset is the one designed to actually exercise coalescing). This
measures policy bookkeeping overhead under load — not a live network
system's real end-to-end latency, which is exactly why benchmark.md is
explicit about what this number does and does not represent.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/backpressure-bounded-pipelines/code/java" rel="noopener"><code>content/labs/backpressure-bounded-pipelines/code/java/</code></a>
in this site's repository.
