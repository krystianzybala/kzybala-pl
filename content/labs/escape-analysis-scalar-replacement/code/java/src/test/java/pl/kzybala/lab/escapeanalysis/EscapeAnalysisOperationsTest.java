package pl.kzybala.lab.escapeanalysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/escape-analysis-scalar-replacement-fixtures.json). All
 * five variants must sum to the identical total for a given dataset —
 * escape/materialization behavior changes cost, never the result.
 */
class EscapeAnalysisOperationsTest {

    private static void assertDataset(String dataset, long[] xFirst5, long[] yFirst5, long expected) {
        EscapeAnalysisFixtures.Pair pair = EscapeAnalysisFixtures.pairFor(dataset, EscapeAnalysisFixtures.N);
        assertArrayEquals(xFirst5, java.util.Arrays.copyOfRange(pair.x(), 0, 5));
        assertArrayEquals(yFirst5, java.util.Arrays.copyOfRange(pair.y(), 0, 5));
        assertEquals(expected, EscapeAnalysisFixtures.expectedTotal(pair.x(), pair.y()));

        AggregateHolder holder = new AggregateHolder();
        assertEquals(expected, EscapeAnalysisOperations.sumNonEscaping(pair.x(), pair.y()));
        assertEquals(expected, EscapeAnalysisOperations.sumReturnedObject(pair.x(), pair.y()));
        assertEquals(expected, EscapeAnalysisOperations.sumStoredIntoField(pair.x(), pair.y(), holder));
        assertEquals(expected, EscapeAnalysisOperations.sumPassedToOpaqueCall(pair.x(), pair.y()));
        assertEquals(expected, EscapeAnalysisOperations.sumIdentityObserved(pair.x(), pair.y()));
    }

    @Test
    void coordinateAllVariantsAgree() {
        assertDataset("coordinate", new long[] {0, 1, 2, 3, 4}, new long[] {805674, 905471, 320954, 629736, 84162}, 999865210459L);
    }

    @Test
    void resultWrapperAllVariantsAgree() {
        assertDataset("resultWrapper", new long[] {75435, 585150, 59539, 476429, 785191}, new long[] {869484, 806586, 360012, 316142, 143781}, 1000116545743L);
    }

    @Test
    void parserStateAllVariantsAgree() {
        assertDataset("parserState", new long[] {139245, 651899, 615781, 740619, 242304}, new long[] {884718, 14393, 986654, 804012, 226311}, 1000153707544L);
    }

    @Test
    void aggregateRecordComputesSumCorrectly() {
        Aggregate a = new Aggregate(7, 35);
        assertEquals(42L, a.sum());
    }
}
