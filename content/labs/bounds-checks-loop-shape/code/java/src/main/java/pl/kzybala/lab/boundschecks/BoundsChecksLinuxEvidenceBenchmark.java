package pl.kzybala.lab.boundschecks;

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

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 5×3
 * matrix. One operation = one full pass over the declared dataset's
 * 1,000,000 logical elements; backing/slice/permutation construction and
 * the closed-form correctness oracle both run in setup, never in the
 * measured method. perf stat / -prof perfasm (see
 * scripts/performance-lab/labs/bounds-checks-loop-shape.conf) wrap this
 * process externally; this class contributes ns/element only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BoundsChecksLinuxEvidenceBenchmark {

    @Param({"canonical", "opaqueLimit", "irregularIndex", "safeIterator", "unchecked"})
    public String variant;

    @Param({"primitiveArrays", "slicesSubranges", "stridedAccess"})
    public String dataset;

    private int[] backing;
    private int[] perm;
    private int[] copiedSlice; // slicesSubranges + safeIterator only
    private MemorySegment segment;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
        int n = BoundsChecksFixtures.N;
        boolean needsPerm = "irregularIndex".equals(variant);
        if (needsPerm) {
            perm = BoundsChecksFixtures.randomPermutation(BoundsChecksFixtures.SEED, n);
        }

        switch (dataset) {
            case "primitiveArrays" -> {
                backing = BoundsChecksFixtures.buildBacking(n);
                if ("unchecked".equals(variant)) segment = MemorySegment.ofArray(backing);
            }
            case "slicesSubranges" -> {
                backing = BoundsChecksFixtures.buildBacking(2 * n);
                if ("safeIterator".equals(variant)) {
                    copiedSlice = Arrays.copyOfRange(backing, BoundsChecksFixtures.SLICE_START, BoundsChecksFixtures.SLICE_START + n);
                }
                if ("unchecked".equals(variant)) segment = MemorySegment.ofArray(backing);
            }
            case "stridedAccess" -> {
                backing = BoundsChecksFixtures.buildBacking(n * BoundsChecksFixtures.STRIDE);
                if ("unchecked".equals(variant)) segment = MemorySegment.ofArray(backing);
            }
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        }

        long expected = BoundsChecksFixtures.expectedTotal(dataset);
        long actual = runOnce();
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

    private long runOnce() {
        int n = BoundsChecksFixtures.N;
        int stride = BoundsChecksFixtures.STRIDE;
        int sliceStart = BoundsChecksFixtures.SLICE_START;
        return switch (dataset) {
            case "primitiveArrays" -> switch (variant) {
                case "canonical" -> BoundsChecksOperations.primitiveCanonical(backing);
                case "opaqueLimit" -> BoundsChecksOperations.primitiveOpaqueLimit(backing);
                case "irregularIndex" -> BoundsChecksOperations.primitiveIrregularIndex(backing, perm);
                case "safeIterator" -> BoundsChecksOperations.primitiveSafeIterator(backing);
                case "unchecked" -> BoundsChecksOperations.primitiveUnchecked(segment, n);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            case "slicesSubranges" -> switch (variant) {
                case "canonical" -> BoundsChecksOperations.sliceCanonical(backing, sliceStart, sliceStart + n);
                case "opaqueLimit" -> BoundsChecksOperations.sliceOpaqueLimit(backing, sliceStart, sliceStart + n);
                case "irregularIndex" -> BoundsChecksOperations.sliceIrregularIndex(backing, perm, sliceStart);
                case "safeIterator" -> BoundsChecksOperations.sliceSafeIterator(copiedSlice);
                case "unchecked" -> BoundsChecksOperations.sliceUnchecked(segment, sliceStart, n);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            case "stridedAccess" -> switch (variant) {
                case "canonical" -> BoundsChecksOperations.stridedCanonical(backing, n, stride);
                case "opaqueLimit" -> BoundsChecksOperations.stridedOpaqueLimit(backing, n, stride);
                case "irregularIndex" -> BoundsChecksOperations.stridedIrregularIndex(backing, perm, stride);
                case "safeIterator" -> BoundsChecksOperations.stridedSafeIterator(backing, n, stride);
                case "unchecked" -> BoundsChecksOperations.stridedUnchecked(segment, n, stride);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long sum() {
        return runOnce();
    }
}
