package pl.kzybala.lab.ffminterop;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark over the "numeric transform" dataset comparing the pure
 * Java baseline against every FFI variant. "Pure Rust baseline" is
 * measured separately, in Rust's own Criterion suite (see rust.md) — it
 * has no meaning as a JMH benchmark since it never touches the JVM.
 */
@State(org.openjdk.jmh.annotations.Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class FfmInteropBenchmark {

    private static final int N = 10_000;

    private double[] input;
    private Arena arena;
    private MemorySegment zeroCopySegment;

    @Setup(Level.Trial)
    public void setUp() {
        input = new double[N];
        for (int i = 0; i < N; i++) {
            input[i] = i * 0.5 - 3.0;
        }
        arena = Arena.ofShared();
        zeroCopySegment = arena.allocate((long) N * Double.BYTES);
        MemorySegment.copy(input, 0, zeroCopySegment, ValueLayout.JAVA_DOUBLE, 0, N);
        // One warm call outside the timed region so class loading / JIT
        // compilation of the downcall stub itself isn't attributed to the
        // first measured iteration.
        RustLibrary.transformScalar(1.0);
    }

    @Benchmark
    public void pureJavaBaseline(Blackhole bh) {
        bh.consume(FfmInteropOps.pureJavaBaseline(input));
    }

    @Benchmark
    public void scalarDowncallPerItem(Blackhole bh) {
        bh.consume(FfmInteropOps.scalarDowncall(input));
    }

    @Benchmark
    public void batchedDowncall(Blackhole bh) {
        bh.consume(FfmInteropOps.batchedDowncall(input));
    }

    @Benchmark
    public void zeroCopyBufferDowncall(Blackhole bh) {
        FfmInteropOps.zeroCopyDowncall(zeroCopySegment, N);
        bh.consume(zeroCopySegment);
    }

    @Benchmark
    public void rustToJavaUpcall(Blackhole bh) {
        long[] count = {0};
        FfmInteropOps.upcallTransform(input, v -> count[0]++);
        bh.consume(count[0]);
    }
}
