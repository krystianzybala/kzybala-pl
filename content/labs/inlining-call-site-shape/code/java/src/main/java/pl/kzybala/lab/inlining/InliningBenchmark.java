package pl.kzybala.lab.inlining;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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
 * Companion dev/wiring benchmark for the "Inlining and call-site shape"
 * Performance Lab (kzybala.pl/lab/inlining-call-site-shape/): the five
 * variants over the pricing-functions dataset, unpinned — for local
 * smoke and IDE profiling. Publication evidence comes exclusively from
 * {@link InliningLinuxEvidenceBenchmark} via the native-Linux runner
 * (see benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class InliningBenchmark {

    @Param({"monomorphic", "bimorphic", "megamorphic", "switchDispatch", "oversizedCallee"})
    public String variant;

    private long[] inputs;

    @Setup(Level.Trial)
    public void setup() {
        inputs = InliningFixtures.pricingFunctions(InliningFixtures.N);
    }

    /** One operation = one full pass over 1,000,000 elements. */
    @Benchmark
    public long sum() {
        return InliningOperations.run(variant, inputs);
    }
}
