package pl.kzybala.lab.branchprediction;

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
 * Companion dev/wiring benchmark for the "Branch prediction and data
 * distribution" Performance Lab (kzybala.pl/lab/branch-prediction/):
 * the same four variants as the publication benchmark, over the byte-flags
 * dataset only, unpinned — for local smoke and IDE profiling. Publication
 * evidence comes exclusively from {@link BranchPredictionLinuxEvidenceBenchmark}
 * via the native-Linux runner (see benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BranchPredictionBenchmark {

    @Param({"biased9010", "random5050", "sorted", "branchless"})
    public String variant;

    private int[] values;

    @Setup(Level.Trial)
    public void setup() {
        int[] random5050 = BranchPredictionFixtures.biasedValues(
            50, BranchPredictionFixtures.BYTE_THRESHOLD, 256, BranchPredictionFixtures.SEED, BranchPredictionFixtures.N);
        values = switch (variant) {
            case "biased9010" -> BranchPredictionFixtures.biasedValues(
                90, BranchPredictionFixtures.BYTE_THRESHOLD, 256, BranchPredictionFixtures.SEED, BranchPredictionFixtures.N);
            case "random5050", "branchless" -> random5050;
            case "sorted" -> BranchPredictionFixtures.sortedAscending(random5050);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    /** One operation = one full pass over 1,000,000 elements (setup excluded). */
    @Benchmark
    public long filteredSum() {
        return "branchless".equals(variant)
            ? BranchPredictionFixtures.filteredSumBranchless(values, BranchPredictionFixtures.BYTE_THRESHOLD)
            : BranchPredictionFixtures.filteredSumBranchy(values, BranchPredictionFixtures.BYTE_THRESHOLD);
    }
}
