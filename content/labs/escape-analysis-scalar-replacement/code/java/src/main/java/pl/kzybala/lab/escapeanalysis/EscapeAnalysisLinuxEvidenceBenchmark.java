package pl.kzybala.lab.escapeanalysis;

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
 * matrix. One operation = one full pass over 1,000,000 elements,
 * constructing one {@link Aggregate} per element via the variant's usage
 * pattern; dataset generation and the correctness oracle both run in
 * setup, never in the measured method. {@code -prof gc} / JFR allocation
 * profiling (see
 * scripts/performance-lab/labs/escape-analysis-scalar-replacement.conf)
 * wrap this process externally; this class contributes ns/op only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class EscapeAnalysisLinuxEvidenceBenchmark {

    @Param({"nonEscaping", "returnedObject", "storedIntoField", "passedToOpaqueCall", "identityObserved"})
    public String variant;

    @Param({"coordinate", "resultWrapper", "parserState"})
    public String dataset;

    private long[] x;
    private long[] y;
    private AggregateHolder holder;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
        EscapeAnalysisFixtures.Pair pair = EscapeAnalysisFixtures.pairFor(dataset, EscapeAnalysisFixtures.N);
        x = pair.x();
        y = pair.y();
        holder = new AggregateHolder();

        long expected = EscapeAnalysisFixtures.expectedTotal(x, y);
        long actual = EscapeAnalysisOperations.run(variant, x, y, holder);
        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long sum() {
        return EscapeAnalysisOperations.run(variant, x, y, holder);
    }
}
