package pl.kzybala.lab.gctail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/gc-tail-latency-fixtures.json). All five variants must
 * sum to the identical total for a given dataset — allocation pattern
 * and collector choice change GC pressure, never the result.
 */
class GcTailOperationsTest {

    private static void assertDataset(String dataset, long[] first5, long expected) {
        long[] values = GcTailFixtures.valuesFor(dataset, GcTailFixtures.N);
        assertArrayEquals(first5, java.util.Arrays.copyOfRange(values, 0, 5));
        assertEquals(expected, GcTailFixtures.expectedTotal(values));

        int extraSize = GcTailFixtures.extraSizeFor(dataset);
        assertEquals(expected, GcTailOperations.sumLowAllocationReuse(values, extraSize));
        assertEquals(expected, GcTailOperations.sumSteadyHighAllocation(values, extraSize));
        assertEquals(expected, GcTailOperations.sumBurstyAllocation(values, extraSize));
        assertEquals(expected, GcTailOperations.sumGrowingLiveSet(values, extraSize));
        assertEquals(expected, GcTailOperations.sumCollectorMatrix(values, extraSize));
    }

    @Test
    void objectGraphChurnAllVariantsAgree() {
        assertDataset("objectGraphChurn", new long[] {0, 1, 2, 3, 4}, 499999500000L);
    }

    @Test
    void messagePipelineAllVariantsAgree() {
        assertDataset("messagePipeline", new long[] {185660, 657890, 900124, 364735, 174128}, 500229738955L);
    }

    @Test
    void retainedCacheAllVariantsAgree() {
        assertDataset("retainedCache", new long[] {193277, 370083, 430581, 204826, 290517}, 499839503787L);
    }
}
