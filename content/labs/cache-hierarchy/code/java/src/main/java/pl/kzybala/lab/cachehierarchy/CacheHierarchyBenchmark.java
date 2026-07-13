package pl.kzybala.lab.cachehierarchy;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "Cache hierarchy" Performance Lab
 * (kzybala.pl/lab/cache-hierarchy/). Measures pointer-chase latency —
 * sequential vs. random traversal order, over a working set that fits in
 * L1 vs. one that exceeds the last-level cache. See benchmark.md for
 * environment and methodology disclosure.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CacheHierarchyBenchmark {

    // 16 KB of longs — comfortably fits inside L1D on any current desktop/laptop core.
    private static final int SMALL_SIZE = 2_048;
    // 128 MB of longs — exceeds any consumer last-level cache.
    private static final int LARGE_SIZE = 16_777_216;
    private static final int CHASES = 1_000_000;

    private long[] sequentialSmallTable;
    private long[] randomSmallTable;
    private long[] sequentialLargeTable;
    private long[] randomLargeTable;

    @Setup(Level.Trial)
    public void setup() {
        sequentialSmallTable = ChaseTables.sequentialCycle(SMALL_SIZE);
        randomSmallTable = ChaseTables.randomCycle(SMALL_SIZE, 1);
        sequentialLargeTable = ChaseTables.sequentialCycle(LARGE_SIZE);
        randomLargeTable = ChaseTables.randomCycle(LARGE_SIZE, 2);
    }

    private static long chase(long[] next, int steps) {
        long idx = 0;
        for (int i = 0; i < steps; i++) idx = next[(int) idx];
        return idx; // returned, not discarded, so the JIT cannot eliminate the loop as dead code.
    }

    @Benchmark
    public long sequentialSmall() { return chase(sequentialSmallTable, CHASES); }

    @Benchmark
    public long randomSmall() { return chase(randomSmallTable, CHASES); }

    @Benchmark
    public long sequentialLarge() { return chase(sequentialLargeTable, CHASES); }

    @Benchmark
    public long randomLarge() { return chase(randomLargeTable, CHASES); }
}
