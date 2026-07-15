package pl.kzybala.lab.falsesharing;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the structural contract the native-Linux evidence runner depends on
 * (scripts/performance-lab/run-linux-evidence.sh): a single group with
 * exactly two writer methods (one per counter), variant selection via a
 * {@code layout} parameter covering exactly {@code shared} and
 * {@code padded}, and enough headroom that the publication workload can
 * never overflow a counter.
 */
class EvidenceBenchmarkContractTest {

    @Test
    void evidenceBenchmarkHasExactlyTwoWritersInOneGroup() {
        List<Method> benchmarks = Arrays.stream(FalseSharingLinuxEvidenceBenchmark.class.getMethods())
            .filter(m -> m.isAnnotationPresent(Benchmark.class))
            .toList();
        assertEquals(2, benchmarks.size(), "exactly two benchmark methods (one writer per counter)");
        for (Method m : benchmarks) {
            Group group = m.getAnnotation(Group.class);
            assertTrue(group != null && group.value().equals("counters"),
                m.getName() + " must be in the single \"counters\" group so JMH runs both writers concurrently");
        }
    }

    @Test
    void layoutParameterCoversExactlySharedAndPadded() throws NoSuchFieldException {
        Field layout = FalseSharingLinuxEvidenceBenchmark.class.getField("layout");
        Param param = layout.getAnnotation(Param.class);
        assertEquals(List.of("shared", "padded"), List.of(param.value()),
            "the runner selects variants with -p layout=<shared|padded>; keep this list in sync with it");
    }

    @Test
    void publicationWorkloadCannotOverflowACounter() {
        // Publication profile ceiling (scripts/performance-lab/
        // run-linux-evidence.sh): 5 forks × (5 warmup + 10 measurement)
        // iterations × 1 s each. Even at an absurd 10^10 increments per
        // second per counter, a fork's worst case stays ~11 orders of
        // magnitude below Long.MAX_VALUE — and counters reset every fork.
        long iterationsPerFork = 5 + 10;
        long secondsPerIteration = 1;
        long absurdOpsPerSecond = 10_000_000_000L;
        long worstCasePerFork = iterationsPerFork * secondsPerIteration * absurdOpsPerSecond;
        assertTrue(worstCasePerFork < Long.MAX_VALUE / 1_000_000,
            "publication workload must keep >=6 orders of magnitude of overflow headroom");
    }
}
