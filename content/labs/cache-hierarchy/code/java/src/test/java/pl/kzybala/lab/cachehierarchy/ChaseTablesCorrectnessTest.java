package pl.kzybala.lab.cachehierarchy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness gate for the chase tables — runs before any timing is
 * trusted. The checksum constants are the shared fixture
 * {@code ../fixtures/cache-hierarchy-fixtures.json}; the Rust suite pins
 * exactly the same values, proving both languages chase byte-identical
 * datasets (same xorshift64 PRNG, same Sattolo walk).
 */
class ChaseTablesCorrectnessTest {

    private static void assertSingleCycle(long[] next) {
        boolean[] visited = new boolean[next.length];
        long idx = 0;
        long indexSum = 0;
        for (int step = 0; step < next.length; step++) {
            idx = next[(int) idx];
            assertTrue(idx >= 0 && idx < next.length, "index out of range");
            assertTrue(!visited[(int) idx], "cycle revisited index " + idx + " before covering all elements");
            visited[(int) idx] = true;
            indexSum += idx;
        }
        assertEquals(0, idx, "cycle must return to the start after exactly size steps");
        // every index visited exactly once => sum is n(n-1)/2
        assertEquals((long) next.length * (next.length - 1) / 2, indexSum);
    }

    @Test
    void sequentialAndRandomTablesAreSingleFullCycles() {
        for (int size : new int[] {2, 64, 2048, 12345}) {
            assertSingleCycle(ChaseTables.sequentialCycle(size));
            assertSingleCycle(ChaseTables.randomCycle(size, 42));
        }
    }

    @Test
    void traversalChecksumsMatchTheSharedCrossLanguageFixture() {
        assertEquals(8738039620073195968L, ChaseTables.traversalChecksum(ChaseTables.randomCycle(2048, 42)));
        assertEquals(6272464722101566464L, ChaseTables.traversalChecksum(ChaseTables.sequentialCycle(2048)));
        assertEquals(-7097521173149448694L, ChaseTables.traversalChecksum(ChaseTables.randomCycle(12345, 42)));
        assertEquals(6737410350348517348L, ChaseTables.traversalChecksum(ChaseTables.sequentialCycle(12345)));
        assertEquals(2077544042594837246L, ChaseTables.traversalChecksum(ChaseTables.randomCycle(2048, 7)));
    }

    @Test
    void randomCycleIsDeterministicPerSeedAndDiffersAcrossSeeds() {
        assertEquals(
            ChaseTables.traversalChecksum(ChaseTables.randomCycle(2048, 42)),
            ChaseTables.traversalChecksum(ChaseTables.randomCycle(2048, 42)));
        assertTrue(
            ChaseTables.traversalChecksum(ChaseTables.randomCycle(2048, 42))
                != ChaseTables.traversalChecksum(ChaseTables.randomCycle(2048, 7)));
    }

    @Test
    void detectedCacheTopologyProducesOrderedWorkingSets() {
        CacheTopology topology = CacheTopology.detect();
        assertTrue(topology.l1dBytes() > 0);
        assertTrue(topology.l2Bytes() >= topology.l1dBytes());
        assertTrue(topology.llcBytes() >= topology.l2Bytes());
        assertTrue(topology.workingSetElements("l1") < topology.workingSetElements("l2"));
        assertTrue(topology.workingSetElements("l2") < topology.workingSetElements("llc"));
        assertTrue(topology.workingSetElements("llc") < topology.workingSetElements("memory"));
    }
}
