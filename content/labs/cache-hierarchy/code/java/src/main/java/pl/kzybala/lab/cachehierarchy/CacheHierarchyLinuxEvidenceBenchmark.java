package pl.kzybala.lab.cachehierarchy;

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
 * worker performing a dependent pointer chase (a single-cycle table — the
 * separate pointer-chasing scenario this lab implements; plain-stride
 * variants are a future scenario axis) over a working set sized against
 * the DETECTED cache topology, never a hardcoded byte count. One operation
 * = exactly {@link #CHASE_STEPS} dependent loads. Table construction,
 * checksum validation and topology detection happen in setup; nothing
 * allocates in the measured path. The resolved topology and chosen sizes
 * are written to {@code cache-topology-<pid>.json} next to the placement
 * evidence, so every imported record can bind its scenario to the actual
 * hardware manifest.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CacheHierarchyLinuxEvidenceBenchmark {

    static final int CHASE_STEPS = 1_000_000;
    static final long RANDOM_SEED = 42;

    @Param({"sequential", "random"})
    public String pattern;

    @Param({"l1", "l2", "llc", "memory"})
    public String workingSet;

    private long[] next;
    private long idx;
    private long steps;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("chaser", WorkerPin.CPU_A);
        }
        CacheTopology topology = CacheTopology.detect();
        int elements = topology.workingSetElements(workingSet);
        next = switch (pattern) {
            case "sequential" -> ChaseTables.sequentialCycle(elements);
            case "random" -> ChaseTables.randomCycle(elements, RANDOM_SEED);
            default -> throw new IllegalStateException("unknown pattern: " + pattern);
        };
        // Correctness before timing: a full-cycle traversal must visit every
        // element exactly once (index sum n(n-1)/2) — cheap, deterministic,
        // and catches a broken table before a single measured operation.
        long indexSum = 0;
        long probe = 0;
        for (int i = 0; i < elements; i++) {
            probe = next[(int) probe];
            indexSum += probe;
        }
        if (probe != 0 || indexSum != (long) elements * (elements - 1) / 2) {
            throw new IllegalStateException("chase table failed the single-cycle oracle — dataset invalid");
        }
        String dir = System.getProperty("plab.placementDir");
        if (dir != null) {
            Files.writeString(
                Path.of(dir, "cache-topology-" + ProcessHandle.current().pid() + ".json"),
                topology.toJson(workingSet, elements) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        idx = 0;
        steps = 0;
    }

    /** One operation = CHASE_STEPS dependent loads through the cycle. */
    @Benchmark
    public long chase() {
        long i = idx;
        long[] table = next;
        for (int s = 0; s < CHASE_STEPS; s++) {
            i = table[(int) i];
        }
        idx = i;   // carried between invocations — the chain never breaks
        steps += CHASE_STEPS;
        return i;  // consumed by JMH: no dead-code elimination
    }

    @TearDown(Level.Trial)
    public void validate() {
        if (steps == 0) {
            throw new IllegalStateException("no chase steps executed — run is invalid");
        }
        if (idx < 0 || idx >= next.length) {
            throw new IllegalStateException("chase index escaped the table — run is invalid");
        }
        if (pin != null) pin.verifyAndRecord();
    }
}
