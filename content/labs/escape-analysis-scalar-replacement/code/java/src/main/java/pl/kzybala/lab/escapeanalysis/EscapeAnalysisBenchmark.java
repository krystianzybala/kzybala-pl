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
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Companion dev/wiring benchmark for the "Escape analysis and scalar
 * replacement" Performance Lab
 * (kzybala.pl/lab/escape-analysis-scalar-replacement/): the five
 * variants over the coordinate dataset, unpinned — for local smoke and
 * IDE profiling. Run with {@code -prof gc} to see B/op collapse to zero
 * for the variants C2 actually scalar-replaces. Publication evidence
 * comes exclusively from {@link EscapeAnalysisLinuxEvidenceBenchmark} via
 * the native-Linux runner (see benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class EscapeAnalysisBenchmark {

    @Param({"nonEscaping", "returnedObject", "storedIntoField", "passedToOpaqueCall", "identityObserved"})
    public String variant;

    private long[] x;
    private long[] y;
    private AggregateHolder holder;

    @Setup(Level.Trial)
    public void setup() {
        EscapeAnalysisFixtures.Pair pair = EscapeAnalysisFixtures.coordinate(EscapeAnalysisFixtures.N);
        x = pair.x();
        y = pair.y();
        holder = new AggregateHolder();
    }

    /** One operation = one full pass over 1,000,000 elements. */
    @Benchmark
    public long sum() {
        return EscapeAnalysisOperations.run(variant, x, y, holder);
    }
}
