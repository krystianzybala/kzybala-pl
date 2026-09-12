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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, workingSet) cell, sized against
 * the DETECTED cache topology, never a hardcoded byte count. One
 * operation = one full pass over the working set's elements in the
 * variant's traversal order; dataset generation, the traversal-order
 * build and the {@code n*(n-1)/2} correctness oracle all run in setup,
 * never in the measured method. The resolved topology and chosen element
 * count are written to {@code cache-locality-topology-<pid>.json} next to
 * the placement evidence.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CacheLocalityLinuxEvidenceBenchmark {

    @Param({"sequential", "random", "pointerChase", "blocked"})
    public String variant;

    @Param({"l1", "l2", "llc", "2xllc", "large"})
    public String workingSet;

    private int elements;
    private int side; // only meaningful for "blocked"
    private int[] perm;
    private long[] next;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("scanner", WorkerPin.CPU_A);
        }
        CacheTopology topology = CacheTopology.detect();
        elements = topology.workingSetElements(workingSet);

        long expected;
        switch (variant) {
            case "sequential" -> expected = CacheLocalityFixtures.expectedTotal(elements);
            case "random" -> {
                perm = CacheLocalityFixtures.randomPermutation(CacheLocalityFixtures.SEED, elements);
                expected = CacheLocalityFixtures.expectedTotal(elements);
            }
            case "pointerChase" -> {
                next = CacheLocalityFixtures.sattoloNext(CacheLocalityFixtures.SEED, elements);
                expected = CacheLocalityFixtures.expectedTotal(elements);
            }
            case "blocked" -> {
                side = (int) Math.sqrt(elements);
                elements = side * side; // exact square used by the tiled traversal
                expected = CacheLocalityFixtures.expectedTotal(elements);
            }
            default -> throw new IllegalStateException("unknown variant: " + variant);
        }

        long actual = runOnce();
        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " workingSet=" + workingSet
                    + ": expected " + expected + " got " + actual);
        }

        String dir = System.getProperty("plab.placementDir");
        if (dir != null) {
            Files.writeString(
                Path.of(dir, "cache-locality-topology-" + ProcessHandle.current().pid() + ".json"),
                topology.toJson(workingSet, elements) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    private long runOnce() {
        return switch (variant) {
            case "sequential" -> CacheLocalityFixtures.sumSequential(elements);
            case "random" -> CacheLocalityFixtures.sumPermuted(perm);
            case "pointerChase" -> CacheLocalityFixtures.sumPointerChase(next);
            case "blocked" -> CacheLocalityFixtures.sumBlocked(side, CacheLocalityFixtures.BLOCK_SIZE);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    /** One operation = one full pass over the resolved working set (setup excluded). */
    @Benchmark
    public long sum() {
        return runOnce();
    }
}
