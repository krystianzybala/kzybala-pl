package pl.kzybala.lab.threadpercore;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "Thread-per-Core Architecture" Performance
 * Lab (kzybala.pl/lab/thread-per-core/). Measures aggregate increment
 * throughput at 4 threads under two architectures: a shared counter pool
 * where every thread contends for one lock regardless of which logical
 * partition it touches, versus thread-per-core ownership where each
 * thread increments only its own, unshared counter. See benchmark.md for
 * environment and methodology disclosure.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ThreadPerCoreBenchmark {

    private static final int PARTITIONS = 4;
    private final SharedCounterPool sharedPool = new SharedCounterPool(PARTITIONS);

    @Benchmark
    @Threads(4)
    public long sharedPoolIncrement() {
        int partition = ThreadLocalRandom.current().nextInt(PARTITIONS);
        return sharedPool.increment(partition);
    }

    @State(Scope.Thread)
    public static class OwnedState {
        final PartitionedCounter counter = new PartitionedCounter();
    }

    @Benchmark
    @Threads(4)
    public long ownedPartitionIncrement(OwnedState state) {
        return state.counter.increment();
    }
}
