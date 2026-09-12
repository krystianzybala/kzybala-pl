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
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

/**
 * Companion dev/wiring benchmark for the "Bounds checks and loop shape"
 * Performance Lab (kzybala.pl/lab/bounds-checks-loop-shape/): the five
 * variants over the primitive-arrays dataset only, unpinned — for local
 * smoke and IDE profiling. Publication evidence comes exclusively from
 * {@link BoundsChecksLinuxEvidenceBenchmark} via the native-Linux runner
 * (see benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class BoundsChecksBenchmark {

    @Param({"canonical", "opaqueLimit", "irregularIndex", "safeIterator", "unchecked"})
    public String variant;

    private int[] backing;
    private int[] perm;
    private MemorySegment segment;

    @Setup(Level.Trial)
    public void setup() {
        backing = BoundsChecksFixtures.buildBacking(BoundsChecksFixtures.N);
        if ("irregularIndex".equals(variant)) {
            perm = BoundsChecksFixtures.randomPermutation(BoundsChecksFixtures.SEED, BoundsChecksFixtures.N);
        }
        if ("unchecked".equals(variant)) {
            segment = MemorySegment.ofArray(backing);
        }
    }

    /** One operation = one full pass over 1,000,000 elements. */
    @Benchmark
    public long sum() {
        return switch (variant) {
            case "canonical" -> BoundsChecksOperations.primitiveCanonical(backing);
            case "opaqueLimit" -> BoundsChecksOperations.primitiveOpaqueLimit(backing);
            case "irregularIndex" -> BoundsChecksOperations.primitiveIrregularIndex(backing, perm);
            case "safeIterator" -> BoundsChecksOperations.primitiveSafeIterator(backing);
            case "unchecked" -> BoundsChecksOperations.primitiveUnchecked(segment, BoundsChecksFixtures.N);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
