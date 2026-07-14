# Thread-per-core architecture — Java

## Owned partition (no synchronization)

```java
public final class PartitionedCounter {
    private long value;
    public long increment() { return ++value; }
    public long get() { return value; }
}
```

One instance per core/partition. No synchronization at all — correct only
because exactly one thread (the one that owns this partition) ever calls
`increment()`. This is the same shape as `SingleWriterCounter` in the
[CAS Contention](/lab/cas-contention/) lab's code, generalized here to be
the primary architecture rather than a one-off contrast.

## Shared counter pool (the baseline being contrasted)

```java
public final class SharedCounterPool {
    private final long[] counters;
    private final ReentrantLock lock = new ReentrantLock();

    public SharedCounterPool(int partitions) { counters = new long[partitions]; }

    public long increment(int partition) {
        lock.lock();
        try {
            return ++counters[partition];
        } finally {
            lock.unlock();
        }
    }
}
```

Every partition's counter lives in one array guarded by one lock. Any
worker thread may increment any partition — but only one increment, on
any partition, proceeds at a time, because they all serialize on the same
lock. This is the code-level shape of the "shared worker pool" the theory
section describes: flexible (any worker can touch any partition), but
every touch pays the lock's cost regardless of how many worker threads
exist.

## JMH benchmark

```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ThreadPerCoreBenchmark {
    private static final int PARTITIONS = 4;
    private final SharedCounterPool sharedPool = new SharedCounterPool(PARTITIONS);

    @Benchmark @Threads(4)
    public long sharedPoolIncrement() {
        int partition = ThreadLocalRandom.current().nextInt(PARTITIONS);
        return sharedPool.increment(partition);
    }

    @State(Scope.Thread)
    public static class OwnedState {
        final PartitionedCounter counter = new PartitionedCounter();
    }

    @Benchmark @Threads(4)
    public long ownedPartitionIncrement(OwnedState state) {
        return state.counter.increment();
    }
}
```

The shared `SharedCounterPool` field (`Scope.Benchmark`) is the one
lock-guarded pool every `sharedPoolIncrement` thread contends for;
`OwnedState` (`Scope.Thread`) gives `ownedPartitionIncrement` a private,
unshared counter per thread — the code-level difference between a shared
worker pool and thread-per-core ownership, benchmarked at the same thread
count so the only variable is whether the state is shared.

The runnable Maven/JMH project (with correctness tests) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/thread-per-core/code/java" rel="noopener"><code>content/labs/thread-per-core/code/java/</code></a>
in this site's repository.
