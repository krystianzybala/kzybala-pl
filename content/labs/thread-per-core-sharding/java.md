# Thread-per-core and shared-nothing sharding — Java

## Shared fixtures and workload plan

```java
public final class TpcFixtures {
    public static final int KEY_COUNT = 16;
    public static final int SHARD_COUNT = 4;
    public static final int REQUESTS_PER_KEY_UNIFORM = 500;
    public static final int REQUESTER_THREADS = 4;
    public static final int SKEWED_HOT_KEY_REQUESTS = 4_000;
    public static final int SKEWED_COLD_KEY_REQUESTS = 200;

    public static int shardFor(int key) { return key % SHARD_COUNT; }
}
```

`WorkloadPlan` builds a deterministic flat array of key requests (each
key repeated exactly its expected count) and splits it into
`REQUESTER_THREADS` contiguous chunks for parallel submission —
identical, byte-for-byte, to
`code/fixtures/thread-per-core-sharding-fixtures.json` and to the Rust
crate's own `fixtures`/`WorkloadPlan` (rust.md). The correctness oracle
(`TpcResult.isCorrectUniform()`/`isCorrectSkewed()`) checks every key's
exact expected final count.

## Shared concurrent map

```java
public static TpcResult run(WorkloadPlan plan) {
    AtomicLongArray counters = new AtomicLongArray(KEY_COUNT);
    // every requester thread: counters.incrementAndGet(key) directly, any key
}
```

No partitioning at all — modeled as a flat array of atomic per-key
counters (the update-in-place shape a real concurrent map offers via
`compute`/`merge`), so the measured cost is per-key atomic contention
itself, not incidental hash-map bucket mechanics.

## Fixed shards with mutex

`MutexShardsKernel` partitions keys into `SHARD_COUNT` shards via the
same `shardFor(key)` function every variant uses, each guarded by its own
`ReentrantLock`. Any requester may update any key, but must hold that
key's shard lock first.

## Single-writer thread-per-core

```java
public static TpcResult run(WorkloadPlan plan) {
    // SHARD_COUNT owner threads, each with its own ConcurrentLinkedQueue<Integer> inbox
    // and a private slice of the counters array — only that owner writes it
    // requester threads: inboxes[shardFor(key)].add(key)
    // owner threads: poll their inbox, counters[key]++, no lock needed
}
```

Routing uses a `ConcurrentLinkedQueue` per shard (the same multi-producer/
single-consumer shape this lab's prerequisite [MPSC Queues and Producer
Contention](/lab/mpsc-contention/) lab covers). **This lab's own
benchmark.md shows this variant costing *more* than the mutex-shards
variant for this cheap-per-item workload** — a real, disclosed finding
about routing overhead, not a bug: see benchmark.md's Method section.

## Skewed key distribution

Both `SharedMapKernel`, `MutexShardsKernel` and `SingleWriterKernel`
accept any `WorkloadPlan` — `WorkloadPlan.skewed()` builds the same
mechanism's inputs with key 0 receiving `SKEWED_HOT_KEY_REQUESTS`
requests and every other key receiving `SKEWED_COLD_KEY_REQUESTS`, per
the shared fixture. No new kernel code is needed; skew is a property of
the workload, not the mechanism.

## Rebalance simulation

`RebalanceSimulationKernel` runs the single-writer mechanism in two
half-sized phases with different key-to-shard mappings, fully joining
every phase-1 owner thread before phase 2 starts (a full stop-the-world
model — see theory.md's Assumptions and scope for exactly what this does
and does not simulate). The pause between phases is measured as
`rebalanceCostNanos`.

## Correctness tests

`TpcOperationsTest` asserts, for every mechanism and both datasets (plus
the rebalance simulation): every key reaches its exact expected count.
Run with:

```sh
cd content/labs/thread-per-core-sharding/code/java && mvn test
```

## JMH benchmark

`TpcBenchmark` runs each mechanism/dataset pair (plus the rebalance
simulation) as its own `@Benchmark` method; every kernel spawns and joins
its own worker threads internally, so every measured operation is a
macro measurement of the full request set running to completion (see
benchmark.md's operation definition).

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/thread-per-core-sharding/code/java" rel="noopener"><code>content/labs/thread-per-core-sharding/code/java/</code></a>
in this site's repository.
