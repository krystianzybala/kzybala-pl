# Logging, Metrics and Profiling Overhead — Java

## The disabled-eager-vs-lazy contrast, side by side

```java
case DISABLED_EAGER_LOGGING -> {
    // Trap: the message is built even though the level is disabled.
    String message = "event id=" + id + " checksum=" + checksum;
    formatCount++;
    if (!LOG_LEVEL_DISABLED) { loggedCount++; }
    consumeToPreventDeadCodeElimination(message);
}
case DISABLED_LAZY_LOGGING -> {
    // Guarded: no formatting cost at all when the level is disabled.
    if (!LOG_LEVEL_DISABLED) {
        String message = "event id=" + id + " checksum=" + checksum;
        formatCount++;
        loggedCount++;
        consumeToPreventDeadCodeElimination(message);
    }
}
```

Both branches are given `LOG_LEVEL_DISABLED = true` for this lab's
datasets — the only difference is *where* the guard sits relative to
string construction. `formatCount` makes the difference directly
assertable: the correctness suite checks that eager logging's
`formatCount` equals the call count exactly, while lazy logging's is
zero.

## Async bounded logging: a deterministic accept/drop model

```java
if (asyncOccupancy < params.asyncQueueCapacity()) {
    asyncOccupancy++;
    loggedCount++;
} else {
    droppedCount++;
}
```

This lab's correctness fixture models the bounded queue's accept/drop
decision as a synchronous occupancy counter — deliberately **not** a real
`ArrayBlockingQueue` with a background drain thread, because a real
consumer racing the producer would make the drop count non-deterministic
from one test run to the next, and this repository's correctness suites
must never be flaky. The occupancy check is the same "bounded reject"
shape as the [Backpressure and Bounded Pipelines](/lab/backpressure-bounded-pipelines/)
lab's `BOUNDED_REJECT` policy, applied here to a log queue.

## Metrics labels: cardinality is map growth

```java
int key = params.distinctKeyCount() > 0 ? id % params.distinctKeyCount() : id;
labelCounts.computeIfAbsent(key, k -> new LongAdder()).increment();
```

`labelCounts.size()` after a run is exactly the number of *distinct* keys
seen — the correctness test for `highCardinalityKey` (500 distinct keys
over 2000 events) asserts this directly, making the cardinality-to-memory
relationship measurable rather than anecdotal.

## Sampled tracing: a real stack walk, deterministically sampled

```java
if (params.sampleRate() > 0 && id % params.sampleRate() == 0) {
    StackTraceElement[] trace = Thread.currentThread().getStackTrace();
    sampledCount++;
}
```

`Thread.currentThread().getStackTrace()` performs a genuine stack walk —
this is not a simulated cost. `HotPath.workThroughStack` recurses 12
frames deep for the `stackTracePath` dataset specifically so that walk has
a realistic number of frames to traverse.

## JMH benchmark

`ObsBenchmark` (`@Fork(1)`, 3 warmup + 5 measurement iterations of 1s
each, nanosecond output) runs each variant against the dataset its
mechanism is meant to expose: `errorBurst` for the logging variants,
`highCardinalityKey` for metrics, `stackTracePath` for sampled tracing.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/observability-overhead/code/java" rel="noopener"><code>content/labs/observability-overhead/code/java/</code></a>
in this site's repository.
