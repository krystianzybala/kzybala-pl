package pl.kzybala.lab.spscringbuffer;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "SPSC Ring Buffer" Performance Lab
 * (kzybala.pl/lab/spsc-ring-buffer/). Measures steady-state produce/consume
 * throughput between one producer thread and one consumer thread pinned to
 * the same {@link SpscRingBuffer}, using JMH's {@code @Group} pattern to
 * force the two {@code @Benchmark} methods to run concurrently as a real
 * pipeline rather than independently. See benchmark.md for environment and
 * methodology disclosure.
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class SpscRingBufferBenchmark {

    private final SpscRingBuffer buffer = new SpscRingBuffer(1024);
    private final long[] out = new long[1];

    @Benchmark
    @Group("spsc")
    @GroupThreads(1)
    public void produce() {
        while (!buffer.tryProduce(1)) {
            Thread.onSpinWait();
        }
    }

    @Benchmark
    @Group("spsc")
    @GroupThreads(1)
    public void consume() {
        while (!buffer.tryConsume(out)) {
            Thread.onSpinWait();
        }
    }
}
