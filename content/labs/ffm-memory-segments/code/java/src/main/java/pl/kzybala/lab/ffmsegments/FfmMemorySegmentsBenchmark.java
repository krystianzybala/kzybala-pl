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
 * Companion dev/wiring benchmark for the "Heap vs off-heap with FFM
 * MemorySegment" Performance Lab (kzybala.pl/lab/ffm-memory-segments/):
 * the five storage variants over the {@code fixedRecords} dataset only,
 * unpinned — for local smoke and IDE profiling. Publication evidence
 * comes exclusively from {@link FfmMemorySegmentsLinuxEvidenceBenchmark}
 * via the native-Linux runner (see benchmark.md).
 *
 * <p>{@code sequentialSum} times one full pass (ns/record = ns/op ÷ N);
 * {@code randomAccess} times {@link #RANDOM_ACCESS_K} pseudo-random
 * reads from a fixed, deterministic index stream (ns/access = ns/op ÷
 * K) — the same distinction this lab's metrics require (benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class FfmMemorySegmentsBenchmark {

    private static final int RANDOM_ACCESS_K = 10_000;

    @Param({"heapPrimitiveArray", "confinedSegment", "sharedSegment", "slicedView", "copiedBoundaryCrossing"})
    public String variant;

    private RecordStorage storage;
    private int[] indices;

    @Setup(Level.Trial)
    public void setup() {
        FfmMemorySegmentsFixtures.FixedRecordsSource source =
            FfmMemorySegmentsFixtures.generateFixedRecords(FfmMemorySegmentsFixtures.FIXED_RECORDS_N);
        storage = switch (variant) {
            case "heapPrimitiveArray" -> FixedRecordsStorage.heapPrimitiveArray(source);
            case "confinedSegment" -> FixedRecordsStorage.confinedSegment(source);
            case "sharedSegment" -> FixedRecordsStorage.sharedSegment(source);
            case "slicedView" -> FixedRecordsStorage.slicedView(source);
            case "copiedBoundaryCrossing" -> FixedRecordsStorage.copiedBoundaryCrossing(source);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        indices = FfmMemorySegmentsFixtures.randomIndices(FfmMemorySegmentsFixtures.FIXED_RECORDS_N, RANDOM_ACCESS_K, 950L);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        storage.close();
    }

    @Benchmark
    public long sequentialSum() {
        return storage.sequentialSum();
    }

    @Benchmark
    public long randomAccess() {
        return storage.randomAccess(indices);
    }
}
