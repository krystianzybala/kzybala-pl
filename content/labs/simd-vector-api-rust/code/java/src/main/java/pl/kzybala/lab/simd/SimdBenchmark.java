package pl.kzybala.lab.simd;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Companion dev/wiring benchmark for the "SIMD: Java Vector API and
 * Rust" Performance Lab (kzybala.pl/lab/simd-vector-api-rust/): the
 * five variants over the {@code sumMinMax} dataset only, unpinned — for
 * local smoke and IDE profiling. Publication evidence comes exclusively
 * from {@link SimdLinuxEvidenceBenchmark} via the native-Linux runner
 * (see benchmark.md). {@code jdk.incubator.vector} is an INCUBATING
 * module — {@code --add-modules jdk.incubator.vector} must be passed to
 * the JVM running this benchmark's forked child process explicitly.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class SimdBenchmark {

    @Param({"scalarBaseline", "autoVectorizedCandidate", "explicitSimd", "misalignedInput", "smallTailHeavyInput"})
    public String variant;

    private int[] value;

    @Setup(Level.Trial)
    public void setup() {
        value = SimdFixtures.generateSumMinMax(SimdFixtures.SUM_MIN_MAX_N);
    }

    @Benchmark
    public SumMinMaxKernel.Result sumMinMax() {
        return switch (variant) {
            case "scalarBaseline" -> SumMinMaxKernel.scalarBaseline(value, 0, value.length);
            case "autoVectorizedCandidate" -> SumMinMaxKernel.autoVectorizedCandidate(value, 0, value.length);
            case "explicitSimd" -> SumMinMaxKernel.explicitSimd(value, 0, value.length);
            case "misalignedInput" -> SumMinMaxKernel.explicitSimd(value, 1, value.length - 1);
            case "smallTailHeavyInput" -> SumMinMaxKernel.explicitSimd(value, 0, SimdFixtures.SMALL_TAIL_N);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
