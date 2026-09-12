package pl.kzybala.lab.ffmsegments;

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
 * matrix. Dataset generation and the correctness oracle (every variant
 * must reproduce the identical checksum for a given dataset) both run in
 * setup, never in the measured method. perf stat (cache-misses,
 * page-faults — see
 * scripts/performance-lab/labs/ffm-memory-segments.conf) wraps this
 * process externally; this class contributes ns/op only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class FfmMemorySegmentsLinuxEvidenceBenchmark {

    private static final int RANDOM_ACCESS_K = 10_000;

    @Param({"heapPrimitiveArray", "confinedSegment", "sharedSegment", "slicedView", "copiedBoundaryCrossing"})
    public String variant;

    @Param({"fixedRecords", "largeNumericBuffers", "binaryFrames"})
    public String dataset;

    private RecordStorage storage;
    private int[] indices;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }

        long expected;
        int n;
        switch (dataset) {
            case "fixedRecords" -> {
                FfmMemorySegmentsFixtures.FixedRecordsSource source =
                    FfmMemorySegmentsFixtures.generateFixedRecords(FfmMemorySegmentsFixtures.FIXED_RECORDS_N);
                n = FfmMemorySegmentsFixtures.FIXED_RECORDS_N;
                expected = FfmMemorySegmentsFixtures.expectedFixedRecordsChecksum(source);
                storage = switch (variant) {
                    case "heapPrimitiveArray" -> FixedRecordsStorage.heapPrimitiveArray(source);
                    case "confinedSegment" -> FixedRecordsStorage.confinedSegment(source);
                    case "sharedSegment" -> FixedRecordsStorage.sharedSegment(source);
                    case "slicedView" -> FixedRecordsStorage.slicedView(source);
                    case "copiedBoundaryCrossing" -> FixedRecordsStorage.copiedBoundaryCrossing(source);
                    default -> throw new IllegalStateException("unknown variant: " + variant);
                };
            }
            case "largeNumericBuffers" -> {
                long[] value = FfmMemorySegmentsFixtures.generateLargeBuffer(FfmMemorySegmentsFixtures.LARGE_BUFFER_N);
                n = FfmMemorySegmentsFixtures.LARGE_BUFFER_N;
                expected = FfmMemorySegmentsFixtures.expectedLargeBufferChecksum(value);
                storage = switch (variant) {
                    case "heapPrimitiveArray" -> LargeNumericBufferStorage.heapPrimitiveArray(value);
                    case "confinedSegment" -> LargeNumericBufferStorage.confinedSegment(value);
                    case "sharedSegment" -> LargeNumericBufferStorage.sharedSegment(value);
                    case "slicedView" -> LargeNumericBufferStorage.slicedView(value);
                    case "copiedBoundaryCrossing" -> LargeNumericBufferStorage.copiedBoundaryCrossing(value);
                    default -> throw new IllegalStateException("unknown variant: " + variant);
                };
            }
            case "binaryFrames" -> {
                FfmMemorySegmentsFixtures.BinaryFramesSource source =
                    FfmMemorySegmentsFixtures.generateBinaryFrames(FfmMemorySegmentsFixtures.BINARY_FRAMES_N);
                n = FfmMemorySegmentsFixtures.BINARY_FRAMES_N;
                expected = FfmMemorySegmentsFixtures.expectedBinaryFramesChecksum(source);
                storage = switch (variant) {
                    case "heapPrimitiveArray" -> BinaryFramesStorage.heapPrimitiveArray(source);
                    case "confinedSegment" -> BinaryFramesStorage.confinedSegment(source);
                    case "sharedSegment" -> BinaryFramesStorage.sharedSegment(source);
                    case "slicedView" -> BinaryFramesStorage.slicedView(source);
                    case "copiedBoundaryCrossing" -> BinaryFramesStorage.copiedBoundaryCrossing(source);
                    default -> throw new IllegalStateException("unknown variant: " + variant);
                };
            }
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        }

        long actual = storage.sequentialSum();
        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }
        indices = FfmMemorySegmentsFixtures.randomIndices(n, RANDOM_ACCESS_K, 951L);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (storage != null) storage.close();
        if (pin != null) pin.verifyAndRecord();
    }

    /** One operation = one full sequential pass over the declared dataset (setup excluded). */
    @Benchmark
    public long sequentialSum() {
        return storage.sequentialSum();
    }

    /** One operation = RANDOM_ACCESS_K pseudo-random reads (setup excluded). */
    @Benchmark
    public long randomAccess() {
        return storage.randomAccess(indices);
    }
}
