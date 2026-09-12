package pl.kzybala.lab.boundschecks;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/bounds-checks-loop-shape-fixtures.json). Every variant, on
 * a given dataset, must sum to the identical closed-form total — loop
 * shape and check-elimination eligibility change cost, never the result.
 */
class BoundsChecksOperationsTest {

    private static final int N = BoundsChecksFixtures.N;
    private static final int STRIDE = BoundsChecksFixtures.STRIDE;
    private static final int SLICE_START = BoundsChecksFixtures.SLICE_START;

    @Test
    void randomPermutationMatchesTheCacheLocalityFixture() {
        int[] perm = BoundsChecksFixtures.randomPermutation(42L, N);
        assertEquals(712069, perm[0]);
        assertEquals(917377, perm[1]);
        assertEquals(307023, perm[2]);
        assertEquals(717218, perm[3]);
        assertEquals(274070, perm[4]);
        long sum = 0;
        for (int v : perm) sum += v;
        assertEquals(499999500000L, sum);
    }

    @Test
    void primitiveArraysAllFiveVariantsAgree() {
        int[] backing = BoundsChecksFixtures.buildBacking(N);
        int[] perm = BoundsChecksFixtures.randomPermutation(BoundsChecksFixtures.SEED, N);
        long expected = BoundsChecksFixtures.expectedTotal("primitiveArrays");
        assertEquals(499999500000L, expected);

        assertEquals(expected, BoundsChecksOperations.primitiveCanonical(backing));
        assertEquals(expected, BoundsChecksOperations.primitiveOpaqueLimit(backing));
        assertEquals(expected, BoundsChecksOperations.primitiveIrregularIndex(backing, perm));
        assertEquals(expected, BoundsChecksOperations.primitiveSafeIterator(backing));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = MemorySegment.ofArray(backing);
            assertEquals(expected, BoundsChecksOperations.primitiveUnchecked(segment, N));
        }
    }

    @Test
    void slicesSubrangesAllFiveVariantsAgree() {
        int[] backing = BoundsChecksFixtures.buildBacking(2 * N);
        int[] perm = BoundsChecksFixtures.randomPermutation(BoundsChecksFixtures.SEED, N);
        long expected = BoundsChecksFixtures.expectedTotal("slicesSubranges");
        assertEquals(999999500000L, expected);
        int end = SLICE_START + N;

        assertEquals(expected, BoundsChecksOperations.sliceCanonical(backing, SLICE_START, end));
        assertEquals(expected, BoundsChecksOperations.sliceOpaqueLimit(backing, SLICE_START, end));
        assertEquals(expected, BoundsChecksOperations.sliceIrregularIndex(backing, perm, SLICE_START));
        assertEquals(expected, BoundsChecksOperations.sliceSafeIterator(Arrays.copyOfRange(backing, SLICE_START, end)));

        MemorySegment segment = MemorySegment.ofArray(backing);
        assertEquals(expected, BoundsChecksOperations.sliceUnchecked(segment, SLICE_START, N));
    }

    @Test
    void stridedAccessAllFiveVariantsAgree() {
        int[] backing = BoundsChecksFixtures.buildBacking(N * STRIDE);
        int[] perm = BoundsChecksFixtures.randomPermutation(BoundsChecksFixtures.SEED, N);
        long expected = BoundsChecksFixtures.expectedTotal("stridedAccess");
        assertEquals(1999998000000L, expected);

        assertEquals(expected, BoundsChecksOperations.stridedCanonical(backing, N, STRIDE));
        assertEquals(expected, BoundsChecksOperations.stridedOpaqueLimit(backing, N, STRIDE));
        assertEquals(expected, BoundsChecksOperations.stridedIrregularIndex(backing, perm, STRIDE));
        assertEquals(expected, BoundsChecksOperations.stridedSafeIterator(backing, N, STRIDE));

        MemorySegment segment = MemorySegment.ofArray(backing);
        assertEquals(expected, BoundsChecksOperations.stridedUnchecked(segment, N, STRIDE));
    }
}
