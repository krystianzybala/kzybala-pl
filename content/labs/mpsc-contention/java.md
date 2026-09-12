# MPSC queues and producer contention — Java

## Shared fixtures

```java
public final class MpscFixtures {
    public static final int[] PRODUCER_COUNTS = {2, 4};
    public static final int ITEMS_PER_PRODUCER = 2_000;
    public static final int CAPACITY = 1_024;
    public static final int BATCH_SIZE = 32;

    public static long encode(int producerId, int localSeq) {
        return ((long) producerId << 32) | (localSeq & 0xFFFFFFFFL);
    }
}
```

Identical, byte-for-byte, to `code/fixtures/mpsc-contention-fixtures.json`
and to the Rust crate's own `fixtures` module (rust.md). Every item
encodes its producer id and local sequence number; the shared
`MpscResult.isCorrect()` oracle asserts exact total count, zero
duplicates, and strict per-producer FIFO order across every variant's
output.

## Single shared MPSC (Vyukov's bounded ring)

```java
public final class SharedMpscKernel {
    static final class Ring {
        final AtomicLongArray sequence; // per-cell readiness/reuse signal
        final long[] data;
        final AtomicLong enqueuePos = new AtomicLong(0);
    }

    public static MpscResult run(int producerCount, int itemsPerProducer, int capacity) {
        // producer: CAS-loop claims a position on the shared enqueuePos,
        // writes data, then publishes via the cell's own sequence number
        // ... (see source for the full claim/publish/backpressure loop)
    }
}
```

Every producer contends on the *same* `enqueuePos` — this is the
lab's baseline contention point. A failed CAS is counted and reported
(`MpscResult.casFailures`), never hidden inside a retry loop.

## Batched claims

`BatchedClaimKernel` reuses the exact same ring shape, but a producer
reserves `BATCH_SIZE` contiguous positions with a single
`enqueuePos.getAndAdd(batchSize)` instead of retrying a per-item CAS —
one atomic read-modify-write per batch rather than (at least) one per
item. Each reserved cell still individually waits for the consumer to
have freed it — batching removes contention at the *claim point*, not
the ring's bounded-capacity backpressure.

## Per-producer SPSC fan-in

`PerProducerFanInKernel` gives every producer its own private
single-producer/single-consumer ring — the same reservation/publication
discipline as this site's [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab,
one instance per producer, with zero shared claim point. The consumer
round-robins across all N rings.

## Mutex queue baseline

`MutexQueueKernel` wraps a plain `ArrayDeque` in a single
`ReentrantLock` — the "obviously correct" mechanism every lock-free
variant here is implicitly compared against, measured explicitly instead
of assumed.

## Unbounded library queue comparison

`LibraryQueueKernel` uses `java.util.concurrent.ConcurrentLinkedQueue`
exactly as shipped by the JDK — no reimplementation, no tuning. It is
unbounded (producers are never rejected) and boxes every `long` into a
`Long`; both are disclosed as that variant's own real cost/semantics, not
smoothed over to make a cleaner comparison (see benchmark.md's Method
section on bounded-vs-unbounded non-comparability).

## Correctness tests

`MpscOperationsTest` asserts, for every variant and both correctness-
suite producer counts (2 and 4): exact total count, zero duplicates, and
strict per-producer FIFO order. Run with:

```sh
cd content/labs/mpsc-contention/code/java && mvn test
```

## JMH benchmark

`MpscBenchmark` parameterizes producer count via `@Param({"2","4"})` and
runs each variant as its own `@Benchmark` method; every kernel spawns and
joins its own producer/consumer threads internally, so every measured
operation is a macro measurement of the full fan-in protocol including
thread spawn/join (see benchmark.md's operation definition).

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/mpsc-contention/code/java" rel="noopener"><code>content/labs/mpsc-contention/code/java/</code></a>
in this site's repository.
