package pl.kzybala.lab.cachelocality;

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
 * Companion dev/wiring benchmark for the "Cache Locality and Working-Set
 * Size" Performance Lab (kzybala.pl/lab/cache-locality-working-set/): the
 * four variants over a fixed 1,000,000-element dataset (unpinned) — for
 * local smoke and IDE profiling. Publication evidence, sized against the
 * DETECTED cache topology, comes exclusively from
 * {@link CacheLocalityLinuxEvidenceBenchmark} via the native-Linux runner
 * (see benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class CacheLocalityBenchmark {

    @Param({"sequential", "random", "pointerChase", "blocked"})
    public String variant;

    private int[] perm;
    private long[] next;

    @Setup(Level.Trial)
    public void setup() {
        switch (variant) {
            case "random" -> perm = CacheLocalityFixtures.randomPermutation(CacheLocalityFixtures.SEED, CacheLocalityFixtures.N);
            case "pointerChase" -> next = CacheLocalityFixtures.sattoloNext(CacheLocalityFixtures.SEED, CacheLocalityFixtures.N);
            default -> { /* sequential / blocked need no precomputed order */ }
        }
    }

    /** One operation = one full pass over 1,000,000 elements. */
    @Benchmark
    public long sum() {
        return switch (variant) {
            case "sequential" -> CacheLocalityFixtures.sumSequential(CacheLocalityFixtures.N);
            case "random" -> CacheLocalityFixtures.sumPermuted(perm);
            case "pointerChase" -> CacheLocalityFixtures.sumPointerChase(next);
            case "blocked" -> CacheLocalityFixtures.sumBlocked(CacheLocalityFixtures.SIDE, CacheLocalityFixtures.BLOCK_SIZE);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
