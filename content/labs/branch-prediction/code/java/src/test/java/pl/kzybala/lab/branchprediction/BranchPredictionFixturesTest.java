package pl.kzybala.lab.branchprediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/branch-prediction-fixtures.json). Every value here must
 * match the Rust suite bit-for-bit before any timing is trusted.
 */
class BranchPredictionFixturesTest {

    @Test
    void biased9010ByteFlagsMatchesTheFixture() {
        int[] vals = BranchPredictionFixtures.biasedValues(90, 128, 256, 42L, 1_000_000);
        assertEquals(-2158142668497906325L, BranchPredictionFixtures.checksumInts(vals));
        assertArrayEquals(new int[] {170, 191, 250, 232, 130}, java.util.Arrays.copyOfRange(vals, 0, 5));
        assertEquals(172388675L, BranchPredictionFixtures.filteredSumBranchy(vals, 128));
    }

    @Test
    void biased9010IntThresholdsMatchesTheFixture() {
        int[] vals = BranchPredictionFixtures.biasedValues(90, 500_000, 1_000_000, 42L, 1_000_000);
        assertEquals(-4739254585402386741L, BranchPredictionFixtures.checksumInts(vals));
        assertArrayEquals(new int[] {805674, 905471, 820954, 629736, 584162}, java.util.Arrays.copyOfRange(vals, 0, 5));
        assertEquals(675165658915L, BranchPredictionFixtures.filteredSumBranchy(vals, 500_000));
    }

    @Test
    void biased9010MixedHotColdMatchesTheFixture() {
        BranchPredictionFixtures.Records r = BranchPredictionFixtures.biasedRecords(90, 42L, 1_000_000);
        assertEquals(2027598892701658837L, BranchPredictionFixtures.checksumInts(r.kinds));
        assertEquals(-20623316227680661L, BranchPredictionFixtures.checksumInts(r.payloads));
        assertEquals(460810947L, BranchPredictionFixtures.filteredSumRecordsBranchy(r));
    }

    @Test
    void random5050ByteFlagsMatchesTheFixture() {
        int[] vals = BranchPredictionFixtures.biasedValues(50, 128, 256, 42L, 1_000_000);
        assertEquals(-6276435397034852629L, BranchPredictionFixtures.checksumInts(vals));
        assertArrayEquals(new int[] {42, 63, 122, 232, 2}, java.util.Arrays.copyOfRange(vals, 0, 5));
        assertEquals(95744342L, BranchPredictionFixtures.filteredSumBranchy(vals, 128));
    }

    @Test
    void random5050IntThresholdsMatchesTheFixture() {
        int[] vals = BranchPredictionFixtures.biasedValues(50, 500_000, 1_000_000, 42L, 1_000_000);
        assertEquals(2963978878734898219L, BranchPredictionFixtures.checksumInts(vals));
        assertEquals(374751514134L, BranchPredictionFixtures.filteredSumBranchy(vals, 500_000));
    }

    @Test
    void random5050MixedHotColdMatchesTheFixture() {
        BranchPredictionFixtures.Records r = BranchPredictionFixtures.biasedRecords(50, 42L, 1_000_000);
        assertEquals(4589498116125369640L, BranchPredictionFixtures.checksumInts(r.kinds));
        assertEquals(255808086L, BranchPredictionFixtures.filteredSumRecordsBranchy(r));
    }

    @Test
    void sortedByteFlagsPreservesFilteredSumButChangesArrangement() {
        int[] random = BranchPredictionFixtures.biasedValues(50, 128, 256, 42L, 1_000_000);
        int[] sorted = BranchPredictionFixtures.sortedAscending(random);
        assertArrayEquals(new int[] {0, 0, 0, 0, 0}, java.util.Arrays.copyOfRange(sorted, 0, 5));
        assertEquals(-5030257529232730897L, BranchPredictionFixtures.checksumInts(sorted));
        long sortedSum = BranchPredictionFixtures.filteredSumBranchy(sorted, 128);
        long randomSum = BranchPredictionFixtures.filteredSumBranchy(random, 128);
        assertEquals(95744342L, sortedSum);
        assertEquals(randomSum, sortedSum, "sorting must not change the filtered sum — same multiset");
    }

    @Test
    void sortedIntThresholdsPreservesFilteredSum() {
        int[] random = BranchPredictionFixtures.biasedValues(50, 500_000, 1_000_000, 42L, 1_000_000);
        int[] sorted = BranchPredictionFixtures.sortedAscending(random);
        assertArrayEquals(new int[] {50, 51, 52, 52, 52}, java.util.Arrays.copyOfRange(sorted, 0, 5));
        assertEquals(
            BranchPredictionFixtures.filteredSumBranchy(random, 500_000),
            BranchPredictionFixtures.filteredSumBranchy(sorted, 500_000));
    }

    @Test
    void sortedMixedHotColdPreservesFilteredSum() {
        BranchPredictionFixtures.Records random = BranchPredictionFixtures.biasedRecords(50, 42L, 1_000_000);
        BranchPredictionFixtures.Records sorted = BranchPredictionFixtures.sortedByKind(random);
        assertArrayEquals(new int[] {0, 0, 0, 0, 0}, java.util.Arrays.copyOfRange(sorted.kinds, 0, 5));
        assertEquals(-2080036976520064992L, BranchPredictionFixtures.checksumInts(sorted.kinds));
        assertEquals(-8076951700959590119L, BranchPredictionFixtures.checksumInts(sorted.payloads));
        assertEquals(
            BranchPredictionFixtures.filteredSumRecordsBranchy(random),
            BranchPredictionFixtures.filteredSumRecordsBranchy(sorted));
    }

    @Test
    void branchlessMatchesBranchyOnTheSameRandom5050Data() {
        int[] bytes = BranchPredictionFixtures.biasedValues(50, 128, 256, 42L, 1_000_000);
        assertEquals(
            BranchPredictionFixtures.filteredSumBranchy(bytes, 128),
            BranchPredictionFixtures.filteredSumBranchless(bytes, 128));

        int[] ints = BranchPredictionFixtures.biasedValues(50, 500_000, 1_000_000, 42L, 1_000_000);
        assertEquals(
            BranchPredictionFixtures.filteredSumBranchy(ints, 500_000),
            BranchPredictionFixtures.filteredSumBranchless(ints, 500_000));

        BranchPredictionFixtures.Records records = BranchPredictionFixtures.biasedRecords(50, 42L, 1_000_000);
        assertEquals(
            BranchPredictionFixtures.filteredSumRecordsBranchy(records),
            BranchPredictionFixtures.filteredSumRecordsBranchless(records));
    }

    @Test
    void branchlessMaskNeverProducesAThirdValue() {
        // The mask arithmetic must resolve to exactly 0 or 1 — no other value.
        for (int v : new int[] {0, 1, 127, 128, 129, 255}) {
            int diff = v - 128;
            int keep = (diff >>> 31) ^ 1;
            assertTrue(keep == 0 || keep == 1);
            assertEquals(v >= 128 ? 1 : 0, keep);
        }
    }
}
