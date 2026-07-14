package pl.kzybala.lab.cascontention;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "CAS contention and backoff" Performance Lab
 * (kzybala.pl/lab/cas-contention/). Measures aggregate compare-and-set
 * throughput on one shared counter at 1, 2, 4, and 8 contending threads,
 * contrasted against a single-writer counter with no contention at all.
 * See benchmark.md for environment and methodology disclosure.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CasContentionBenchmark {

    private final CasCounter shared = new CasCounter();

    @Benchmark
    @Threads(1)
    public long casIncrement1Thread() {
        return shared.incrementManually();
    }

    @Benchmark
    @Threads(2)
    public long casIncrement2Threads() {
        return shared.incrementManually();
    }

    @Benchmark
    @Threads(4)
    public long casIncrement4Threads() {
        return shared.incrementManually();
    }

    @Benchmark
    @Threads(8)
    public long casIncrement8Threads() {
        return shared.incrementManually();
    }

    @State(Scope.Thread)
    public static class SingleWriterState {
        final SingleWriterCounter counter = new SingleWriterCounter();
    }

    @Benchmark
    @Threads(1)
    public long singleWriterIncrement(SingleWriterState state) {
        return state.counter.increment();
    }
}
