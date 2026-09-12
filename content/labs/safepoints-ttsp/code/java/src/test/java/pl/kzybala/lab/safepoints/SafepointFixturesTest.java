package pl.kzybala.lab.safepoints;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/safepoints-ttsp-fixtures.json). Every dataset's worker
 * partitioning must sum to the identical total — integer summation is
 * partition-invariant, so worker count and native-call interleaving
 * change TTSP behavior, never the computed result.
 */
class SafepointFixturesTest {

    @Test
    void singleWorkerSumsToTheExpectedTotal() {
        assertEquals(SafepointFixtures.EXPECTED_TOTAL, SafepointFixtures.sumRange(0, SafepointFixtures.TOTAL_ITERATIONS));
    }

    @Test
    void fleetPartitioningSumsToTheIdenticalTotal() {
        long total = 0;
        for (int w = 0; w < SafepointFixtures.FLEET_SIZE; w++) {
            long start = (long) w * SafepointFixtures.PER_FLEET_WORKER_ITERATIONS;
            total += SafepointFixtures.sumRange(start, SafepointFixtures.PER_FLEET_WORKER_ITERATIONS);
        }
        assertEquals(SafepointFixtures.EXPECTED_TOTAL, total);
    }

    @Test
    void chunkedSummationMatchesTheWholeRangeSum() {
        long total = 0;
        for (int c = 0; c < SafepointFixtures.NUM_CHUNKS; c++) {
            total += SafepointFixtures.sumRange((long) c * SafepointFixtures.CHUNK_SIZE, SafepointFixtures.CHUNK_SIZE);
        }
        assertEquals(SafepointFixtures.TOTAL_ITERATIONS, (long) SafepointFixtures.NUM_CHUNKS * SafepointFixtures.CHUNK_SIZE);
        assertEquals(SafepointFixtures.EXPECTED_TOTAL, total);
    }
}
