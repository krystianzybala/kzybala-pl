package pl.kzybala.lab.aosvssoa;

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
 * worker executing exactly one (variant, dataset) cell of the 4×3 matrix.
 * One operation = one full pass over {@link AosVsSoaFixtures#N} records,
 * touching only the hot fields — dataset generation and the
 * layout-invariance correctness oracle (every layout must reproduce the
 * identical {@code hotSum}) both run in setup, never in the measured
 * method. perf stat (cache-references, cache-misses, LLC events — see
 * scripts/performance-lab/labs/aos-vs-soa.conf) wraps this process
 * externally; this class contributes ns/record only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class AosVsSoaLinuxEvidenceBenchmark {

    @Param({"aosHeap", "aosPacked", "soa", "hybrid"})
    public String variant;

    @Param({"marketQuotes", "positions", "spatialPoints"})
    public String dataset;

    private AosHeapLayout heap;
    private AosPackedLayout packed;
    private SoaLayout soa;
    private HybridLayout hybrid;
    private WorkerPin pin;

    private static int coldWords(String dataset) {
        return switch (dataset) {
            case "marketQuotes" -> 4;
            case "positions" -> 8;
            case "spatialPoints" -> 2;
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
        int coldWords = coldWords(dataset);
        AosVsSoaFixtures.Generated data = AosVsSoaFixtures.generate(coldWords, AosVsSoaFixtures.SEED, AosVsSoaFixtures.N);
        long expectedHotSum = AosVsSoaFixtures.expectedHotSum(data.hotA, data.hotB);

        long actualHotSum = switch (variant) {
            case "aosHeap" -> {
                heap = AosHeapLayout.of(data);
                yield heap.sumHot();
            }
            case "aosPacked" -> {
                packed = AosPackedLayout.of(data, coldWords);
                yield packed.sumHot();
            }
            case "soa" -> {
                soa = SoaLayout.of(data, coldWords);
                yield soa.sumHot();
            }
            case "hybrid" -> {
                hybrid = HybridLayout.of(data, coldWords);
                yield hybrid.sumHot();
            }
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        // Correctness before timing: every layout must reproduce the exact
        // same hot sum — layout changes storage, never the data.
        if (actualHotSum != expectedHotSum) {
            throw new IllegalStateException(
                "layout-invariance oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expectedHotSum + " got " + actualHotSum);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (packed != null) packed.close();
        if (pin != null) pin.verifyAndRecord();
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long sumHot() {
        return switch (variant) {
            case "aosHeap" -> heap.sumHot();
            case "aosPacked" -> packed.sumHot();
            case "soa" -> soa.sumHot();
            case "hybrid" -> hybrid.sumHot();
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
