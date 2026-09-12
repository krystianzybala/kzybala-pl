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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 5×3
 * matrix. One operation = one full pass over 1,000,000 inputs,
 * dispatching to the variant's strategy selection; dataset generation and
 * the pairwise correctness oracle (monomorphic==oversizedCallee,
 * megamorphic==switchDispatch) both run in setup, never in the measured
 * method. perf stat / -prof perfasm (see
 * scripts/performance-lab/labs/inlining-call-site-shape.conf) wrap this
 * process externally; this class contributes ns/call only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class InliningLinuxEvidenceBenchmark {

    @Param({"monomorphic", "bimorphic", "megamorphic", "switchDispatch", "oversizedCallee"})
    public String variant;

    @Param({"pricingFunctions", "codecStrategies", "validationRules"})
    public String dataset;

    private long[] inputs;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
        inputs = InliningFixtures.inputsFor(dataset, InliningFixtures.N);

        long actual = InliningOperations.run(variant, inputs);
        long referenceGroup = switch (variant) {
            case "monomorphic", "oversizedCallee" -> InliningOperations.sumMonomorphic(inputs);
            case "megamorphic", "switchDispatch" -> InliningOperations.sumMegamorphic(inputs);
            case "bimorphic" -> InliningOperations.sumBimorphic(inputs);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        if (actual != referenceGroup) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + referenceGroup + " got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long sum() {
        return InliningOperations.run(variant, inputs);
    }
}
