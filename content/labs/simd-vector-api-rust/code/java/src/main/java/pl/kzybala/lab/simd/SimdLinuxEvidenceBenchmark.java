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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 5×4
 * matrix. Dataset generation and the correctness oracle both run in
 * setup, never in the measured method.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class SimdLinuxEvidenceBenchmark {

    @Param({"scalarBaseline", "autoVectorizedCandidate", "explicitSimd", "misalignedInput", "smallTailHeavyInput"})
    public String variant;

    @Param({"sumMinMax", "thresholdFilter", "dotProduct", "byteClassification"})
    public String dataset;

    private int[] intValue;
    private SimdFixtures.DotProductSource dotSource;
    private byte[] byteValue;
    private WorkerPin pin;

    /**
     * {@code misalignedInput}/{@code smallTailHeavyInput} deliberately
     * run over a DIFFERENT input shape than the other three variants
     * (offset by one element; truncated to 17 elements) — the
     * correctness oracle must be computed over the SAME shape the
     * variant actually consumes, or every non-full-length variant would
     * spuriously fail against a full-length expected value. A real bug
     * found while building this lab (java.md).
     */
    private int offsetFor() {
        return "misalignedInput".equals(variant) ? 1 : 0;
    }

    private int lengthFor(int fullLength) {
        return switch (variant) {
            case "misalignedInput" -> fullLength - 1;
            case "smallTailHeavyInput" -> SimdFixtures.SMALL_TAIL_N;
            default -> fullLength;
        };
    }

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }

        switch (dataset) {
            case "sumMinMax" -> {
                intValue = SimdFixtures.generateSumMinMax(SimdFixtures.SUM_MIN_MAX_N);
                SumMinMaxKernel.Result expected =
                    SumMinMaxKernel.scalarBaseline(intValue, offsetFor(), lengthFor(intValue.length));
                SumMinMaxKernel.Result actual = runSumMinMax();
                if (!actual.equals(expected)) {
                    throw new IllegalStateException(
                        "correctness oracle failed for variant=" + variant + " dataset=" + dataset);
                }
            }
            case "thresholdFilter" -> {
                intValue = SimdFixtures.generateThreshold(SimdFixtures.THRESHOLD_N);
                long expected = ThresholdFilterKernel.scalarBaseline(
                    intValue, offsetFor(), lengthFor(intValue.length), SimdFixtures.THRESHOLD);
                long actual = runThresholdFilter();
                if (actual != expected) {
                    throw new IllegalStateException(
                        "correctness oracle failed for variant=" + variant + " dataset=" + dataset);
                }
            }
            case "dotProduct" -> {
                dotSource = SimdFixtures.generateDotProduct(SimdFixtures.DOT_PRODUCT_N);
                double expected = DotProductKernel.scalarBaseline(
                    dotSource.a, dotSource.b, offsetFor(), lengthFor(dotSource.a.length));
                double actual = runDotProduct();
                if (actual != expected) {
                    throw new IllegalStateException(
                        "correctness oracle failed for variant=" + variant + " dataset=" + dataset);
                }
            }
            case "byteClassification" -> {
                byteValue = SimdFixtures.generateByteClassification(SimdFixtures.BYTE_CLASSIFICATION_N);
                long expected =
                    ByteClassificationKernel.scalarBaseline(byteValue, offsetFor(), lengthFor(byteValue.length));
                long actual = runByteClassification();
                if (actual != expected) {
                    throw new IllegalStateException(
                        "correctness oracle failed for variant=" + variant + " dataset=" + dataset);
                }
            }
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    private SumMinMaxKernel.Result runSumMinMax() {
        return switch (variant) {
            case "scalarBaseline" -> SumMinMaxKernel.scalarBaseline(intValue, 0, intValue.length);
            case "autoVectorizedCandidate" -> SumMinMaxKernel.autoVectorizedCandidate(intValue, 0, intValue.length);
            case "explicitSimd" -> SumMinMaxKernel.explicitSimd(intValue, 0, intValue.length);
            case "misalignedInput" -> SumMinMaxKernel.explicitSimd(intValue, 1, intValue.length - 1);
            case "smallTailHeavyInput" -> SumMinMaxKernel.explicitSimd(intValue, 0, SimdFixtures.SMALL_TAIL_N);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private long runThresholdFilter() {
        int t = SimdFixtures.THRESHOLD;
        return switch (variant) {
            case "scalarBaseline" -> ThresholdFilterKernel.scalarBaseline(intValue, 0, intValue.length, t);
            case "autoVectorizedCandidate" -> ThresholdFilterKernel.autoVectorizedCandidate(intValue, 0, intValue.length, t);
            case "explicitSimd" -> ThresholdFilterKernel.explicitSimd(intValue, 0, intValue.length, t);
            case "misalignedInput" -> ThresholdFilterKernel.explicitSimd(intValue, 1, intValue.length - 1, t);
            case "smallTailHeavyInput" -> ThresholdFilterKernel.explicitSimd(intValue, 0, SimdFixtures.SMALL_TAIL_N, t);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private double runDotProduct() {
        double[] a = dotSource.a;
        double[] b = dotSource.b;
        return switch (variant) {
            case "scalarBaseline" -> DotProductKernel.scalarBaseline(a, b, 0, a.length);
            case "autoVectorizedCandidate" -> DotProductKernel.autoVectorizedCandidate(a, b, 0, a.length);
            case "explicitSimd" -> DotProductKernel.explicitSimd(a, b, 0, a.length);
            case "misalignedInput" -> DotProductKernel.explicitSimd(a, b, 1, a.length - 1);
            case "smallTailHeavyInput" -> DotProductKernel.explicitSimd(a, b, 0, SimdFixtures.SMALL_TAIL_N);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private long runByteClassification() {
        return switch (variant) {
            case "scalarBaseline" -> ByteClassificationKernel.scalarBaseline(byteValue, 0, byteValue.length);
            case "autoVectorizedCandidate" -> ByteClassificationKernel.autoVectorizedCandidate(byteValue, 0, byteValue.length);
            case "explicitSimd" -> ByteClassificationKernel.explicitSimd(byteValue, 0, byteValue.length);
            case "misalignedInput" -> ByteClassificationKernel.explicitSimd(byteValue, 1, byteValue.length - 1);
            case "smallTailHeavyInput" -> ByteClassificationKernel.explicitSimd(byteValue, 0, SimdFixtures.SMALL_TAIL_N);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public Object run() {
        return switch (dataset) {
            case "sumMinMax" -> runSumMinMax();
            case "thresholdFilter" -> runThresholdFilter();
            case "dotProduct" -> runDotProduct();
            case "byteClassification" -> runByteClassification();
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }
}
