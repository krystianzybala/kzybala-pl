# Deterministic Low-Latency Pipeline Capstone — Java

## One recurrence, four variants

```java
int numShards = variant == PipelineVariant.NAIVE_OBJECT_QUEUE ? 1 : params.numShards();
int capacity = variant == PipelineVariant.OVERLOAD_PROFILE
        ? Math.max(1, params.capacityPerShard() / 8)
        : (variant == PipelineVariant.NAIVE_OBJECT_QUEUE ? Integer.MAX_VALUE : params.capacityPerShard());
```

`NAIVE_OBJECT_QUEUE` collapses to a single unbounded "shard" — the exact
shape of the anti-pattern theory.md describes. Every other variant uses
`params.numShards()` bounded per-shard queues; `OVERLOAD_PROFILE` reuses
the identical admission logic with capacity deliberately shrunk to force
drops.

## Per-shard bounded admission, the Backpressure lab's policy applied N times

```java
int shard = numShards > 1 ? Math.floorMod(key, numShards) : 0;
if (queues[shard].size() < capacity) {
    queues[shard].addLast(id);
} else {
    dropped++;
}
```

This is deliberately identical in shape to the
[Backpressure and Bounded Pipelines](/lab/backpressure-bounded-pipelines/)
lab's `BOUNDED_REJECT` policy — the only difference here is that it runs
once per shard instead of once globally, which is exactly what "sharding
provides isolation" means operationally.

## Fault/restart: an honest, counted loss

```java
if (variant == PipelineVariant.FAULT_RESTART_PROFILE && tick == params.restartAtTick()) {
    int victim = Math.floorMod(params.restartShard(), numShards);
    restartLoss += queues[victim].size();
    queues[victim].clear();
}
```

Every event still queued in the restarted shard at the moment of restart
is counted as `restartLoss` — never silently merged into `delivered` or
`dropped`, both of which mean something different (a delivered event was
processed; a dropped event was never admitted in the first place; a
restart-lost event was admitted, then lost to a simulated failure).

## Zero-copy decode (the real, non-fixture implementation)

The correctness fixture's `Decision.decisionChecksum(key, eventId,
eventSizeBytes)` is intentionally a pure function with no real byte array
— but the actual pipeline's decode step (exercised by the JMH benchmark
and the real event-replay harness alongside this lab's code) reads a
fixed-layout event directly from a `MemorySegment`/`ByteBuffer` view at
known offsets (key at bytes 0-3, payload following), with no per-event
object allocation — the same zero-copy discipline as
[Fixed Binary Serialization](/lab/fixed-binary-serialization/)'s
ByteBuffer/FFM codecs, applied here as one stage of a larger pipeline
rather than as the lab's sole subject.

## JMH benchmark

`DllpBenchmark` (`@Fork(1)`, 3 warmup + 5 measurement iterations of 1s
each, microsecond output) runs `NAIVE_OBJECT_QUEUE` and `OPTIMIZED`
against `mediumEventsUniformBurst`, and `OVERLOAD_PROFILE`/
`FAULT_RESTART_PROFILE` against `mediumEventsHotKeyBurst` specifically, to
exercise the shard-skew behavior those two variants are meant to reveal.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/deterministic-low-latency-pipeline/code/java" rel="noopener"><code>content/labs/deterministic-low-latency-pipeline/code/java/</code></a>
in this site's repository.
