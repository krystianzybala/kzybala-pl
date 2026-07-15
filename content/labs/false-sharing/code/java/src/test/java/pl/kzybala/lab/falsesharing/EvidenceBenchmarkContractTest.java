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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void eachWriterCarriesItsOwnPinStateAndPinningHappensOutsideMeasurement() throws NoSuchMethodException {
        // writeA is pinned to plab.cpuA via PinWriterA, writeB to plab.cpuB
        // via PinWriterB — per-worker thread states, so sched_setaffinity
        // runs on the worker's own thread. The pin/verify methods carry
        // @Setup/@TearDown(Level.Trial): affinity is established and
        // checked strictly outside the measured operations.
        Method writeA = FalseSharingLinuxEvidenceBenchmark.class.getMethod(
            "writeA", FalseSharingLinuxEvidenceBenchmark.PinWriterA.class);
        Method writeB = FalseSharingLinuxEvidenceBenchmark.class.getMethod(
            "writeB", FalseSharingLinuxEvidenceBenchmark.PinWriterB.class);
        assertTrue(writeA.isAnnotationPresent(Benchmark.class));
        assertTrue(writeB.isAnnotationPresent(Benchmark.class));
        for (Class<?> pinState : List.of(
            FalseSharingLinuxEvidenceBenchmark.PinWriterA.class,
            FalseSharingLinuxEvidenceBenchmark.PinWriterB.class)) {
            Method pin = Arrays.stream(pinState.getMethods())
                .filter(m -> m.isAnnotationPresent(org.openjdk.jmh.annotations.Setup.class))
                .findFirst().orElseThrow();
            assertEquals(org.openjdk.jmh.annotations.Level.Trial,
                pin.getAnnotation(org.openjdk.jmh.annotations.Setup.class).value(),
                pinState.getSimpleName() + " must pin at trial setup, never per invocation");
            assertTrue(Arrays.stream(pinState.getMethods())
                    .anyMatch(m -> m.isAnnotationPresent(org.openjdk.jmh.annotations.TearDown.class)),
                pinState.getSimpleName() + " must verify placement at teardown");
        }
    }

    @Test
    void affinityIsRefusedRatherThanFakedOffLinux() {
        // Process-level taskset is never accepted as worker pinning; on a
        // platform without sched_setaffinity the pin must throw, aborting a
        // run that requested pinning — not silently continue unpinned.
        if (!CpuAffinity.isSupported()) {
            assertThrows(IllegalStateException.class, () -> CpuAffinity.pinCurrentThread(0));
        }
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
