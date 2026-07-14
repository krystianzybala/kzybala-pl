# CAS contention and backoff — Java

## CAS retry loop

```java
public final class CasCounter {
    private final AtomicLong value = new AtomicLong();

    // updateAndGet already implements the retry loop internally — shown
    // manually once below to make the retry explicit.
    public long incrementViaBuiltin() {
        return value.updateAndGet(v -> v + 1);
    }

    public long incrementManually() {
        long old, updated;
        do {
            old = value.get();
            updated = old + 1;
        } while (!value.compareAndSet(old, updated));
        return updated;
    }
}
```

`compareAndSet` returns `false` on a failed attempt without throwing or
blocking — the `do`/`while` loop is the manual form of exactly what
`updateAndGet` does internally. Under contention, this loop's body can run
many times per successful increment; see `benchmark.md` for measured
throughput at 1, 2, 4, and 8 contending threads.

## Single-writer alternative

```java
public final class SingleWriterCounter {
    // No CAS, no atomics — correct only because exactly one thread ever
    // calls increment(). See theory.md "The single-writer alternative".
    private long value;

    public long increment() {
        return ++value;
    }
}
```

No synchronization needed, because nothing else ever touches `value`. See
`benchmark.md` for how dramatically this outperforms even *uncontended*
CAS, and the theory section for why that gap is not a blanket
recommendation to avoid atomics.

## JMH benchmark

```java
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CasContentionBenchmark {

    private final CasCounter shared = new CasCounter();

    @Benchmark @Threads(1)
    public long casIncrement1Thread() { return shared.incrementManually(); }

    @Benchmark @Threads(2)
    public long casIncrement2Threads() { return shared.incrementManually(); }

    @Benchmark @Threads(4)
    public long casIncrement4Threads() { return shared.incrementManually(); }

    @Benchmark @Threads(8)
    public long casIncrement8Threads() { return shared.incrementManually(); }

    @State(Scope.Thread)
    public static class SingleWriterState {
        final SingleWriterCounter counter = new SingleWriterCounter();
    }

    @Benchmark @Threads(1)
    public long singleWriterIncrement(SingleWriterState state) {
        return state.counter.increment();
    }
}
```

`@Threads(N)` on each method fixes its contender count independently within
one JMH run, rather than needing four separate invocations with `-t`. The
shared `CasCounter` field (`Scope.Benchmark`) is the single contended
location every `casIncrement*Threads` benchmark hammers; `SingleWriterState`
(`Scope.Thread`) gives the single-writer benchmark its own private counter
per thread, since it specifically must never be shared.

The runnable Maven/JMH project is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/cas-contention/code/java" rel="noopener"><code>content/labs/cas-contention/code/java/</code></a>
in this site's repository.
