package pl.kzybala.lab.memoryordering;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural gate for the two deliberately separate measured halves:
 * outcome-frequency litmus (a dedicated harness — never JMH, never
 * latency) and per-operation ordering-strength cost (JMH — never outcome
 * counting). Also pins the forbidden-outcome definitions so the imported
 * evidence is interpreted against the exact predicate the harness counted.
 */
class LitmusEvidenceContractTest {

    @Test
    void forbiddenOutcomeDefinitionsAreExactlyTheTextbookPredicates() {
        LitmusEvidenceHarness mp = new LitmusEvidenceHarness("mp", "acqrel");
        assertTrue(mp.isForbidden(1, 0), "MP forbidden: flag seen, data stale");
        assertFalse(mp.isForbidden(0, 0));
        assertFalse(mp.isForbidden(1, 1));
        assertFalse(mp.isForbidden(0, 1));

        LitmusEvidenceHarness sb = new LitmusEvidenceHarness("sb", "volatile");
        assertTrue(sb.isForbidden(0, 0), "SB forbidden: both loads miss both stores");
        assertFalse(sb.isForbidden(1, 0));
        assertFalse(sb.isForbidden(0, 1));
        assertFalse(sb.isForbidden(1, 1));
    }

    @Test
    void litmusHarnessIsNotAJmhBenchmark() {
        // Rare-outcome frequency must never be measured with JMH modes —
        // the harness is a plain main() with persistent workers.
        for (var method : LitmusEvidenceHarness.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class),
                "LitmusEvidenceHarness must not carry @Benchmark methods");
        }
    }

    @Test
    void costBenchmarkCoversExactlyTheFourAccessModes() throws NoSuchFieldException {
        Field mode = MemoryOrderingCostBenchmark.class.getField("accessMode");
        assertEquals(List.of("plain", "opaque", "release", "volatile"),
            List.of(mode.getAnnotation(Param.class).value()),
            "scripts/performance-lab/labs/memory-ordering.conf selects these with -p accessMode=<mode>");
    }

    @Test
    void spinBarrierSynchronizesTwoParties() throws InterruptedException {
        var barrier = new LitmusEvidenceHarness.SpinBarrier();
        final int rounds = 10_000;
        final long[] observed = {0};
        Thread other = new Thread(() -> {
            for (int i = 0; i < rounds; i++) barrier.await();
        });
        other.start();
        for (int i = 0; i < rounds; i++) {
            barrier.await();
            observed[0]++;
        }
        other.join();
        assertEquals(rounds, observed[0]);
    }

    @Test
    void publicationAccountingIsExactSingleThreaded() {
        MemoryOrderingCostBenchmark bench = new MemoryOrderingCostBenchmark();
        bench.accessMode = "release";
        bench.setUp();
        for (int i = 0; i < 1_000; i++) {
            assertEquals(i + 1, bench.publish());
        }
        bench.validate(); // throws if flag/payload/sequence disagree
    }
}
