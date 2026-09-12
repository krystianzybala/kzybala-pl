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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the matrix. One
 * operation = one full pass over {@link BranchPredictionFixtures#N}
 * elements of the declared dataset with the declared variant's technique;
 * dataset generation, sorting and the permutation-invariance correctness
 * oracle all run in setup, never in the measured method. perf stat
 * (branches, branch-misses, cycles, instructions — see
 * scripts/performance-lab/labs/branch-prediction.conf) wraps this process
 * externally; this class contributes ns/element only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BranchPredictionLinuxEvidenceBenchmark {

    @Param({"biased9010", "random5050", "sorted", "branchless"})
    public String variant;

    @Param({"byteFlags", "intThresholds", "mixedHotCold"})
    public String dataset;

    private int[] values; // byteFlags / intThresholds
    private BranchPredictionFixtures.Records records; // mixedHotCold
    private int threshold;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
        boolean useRecords = "mixedHotCold".equals(dataset);
        if (useRecords) {
            setupRecords();
        } else {
            setupValues();
        }
    }

    private void setupValues() {
        threshold = "byteFlags".equals(dataset) ? BranchPredictionFixtures.BYTE_THRESHOLD : BranchPredictionFixtures.INT_THRESHOLD;
        int domain = "byteFlags".equals(dataset) ? 256 : BranchPredictionFixtures.INT_DOMAIN;
        int[] random5050 = BranchPredictionFixtures.biasedValues(
            50, threshold, domain, BranchPredictionFixtures.SEED, BranchPredictionFixtures.N);
        values = switch (variant) {
            case "biased9010" -> BranchPredictionFixtures.biasedValues(
                90, threshold, domain, BranchPredictionFixtures.SEED, BranchPredictionFixtures.N);
            case "random5050", "branchless" -> random5050;
            case "sorted" -> BranchPredictionFixtures.sortedAscending(random5050);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        // Correctness before timing: sorted/branchless must reproduce the
        // random5050 filtered sum exactly — same multiset, only arrangement
        // or technique differs (permutation-invariance oracle).
        if (!"biased9010".equals(variant)) {
            long random5050Sum = BranchPredictionFixtures.filteredSumBranchy(random5050, threshold);
            long thisSum = "branchless".equals(variant)
                ? BranchPredictionFixtures.filteredSumBranchless(values, threshold)
                : BranchPredictionFixtures.filteredSumBranchy(values, threshold);
            if (thisSum != random5050Sum) {
                throw new IllegalStateException(
                    "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                        + ": expected " + random5050Sum + " got " + thisSum);
            }
        }
    }

    private void setupRecords() {
        BranchPredictionFixtures.Records random5050 = BranchPredictionFixtures.biasedRecords(
            50, BranchPredictionFixtures.SEED, BranchPredictionFixtures.N);
        records = switch (variant) {
            case "biased9010" -> BranchPredictionFixtures.biasedRecords(90, BranchPredictionFixtures.SEED, BranchPredictionFixtures.N);
            case "random5050", "branchless" -> random5050;
            case "sorted" -> BranchPredictionFixtures.sortedByKind(random5050);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        if (!"biased9010".equals(variant)) {
            long random5050Sum = BranchPredictionFixtures.filteredSumRecordsBranchy(random5050);
            long thisSum = "branchless".equals(variant)
                ? BranchPredictionFixtures.filteredSumRecordsBranchless(records)
                : BranchPredictionFixtures.filteredSumRecordsBranchy(records);
            if (thisSum != random5050Sum) {
                throw new IllegalStateException(
                    "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                        + ": expected " + random5050Sum + " got " + thisSum);
            }
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long filteredSum() {
        boolean branchless = "branchless".equals(variant);
        if ("mixedHotCold".equals(dataset)) {
            return branchless
                ? BranchPredictionFixtures.filteredSumRecordsBranchless(records)
                : BranchPredictionFixtures.filteredSumRecordsBranchy(records);
        }
        return branchless
            ? BranchPredictionFixtures.filteredSumBranchless(values, threshold)
            : BranchPredictionFixtures.filteredSumBranchy(values, threshold);
    }
}
