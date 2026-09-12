package pl.kzybala.lab.cachelocality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/cache-locality-working-set-fixtures.json). Every value
 * here must match the Rust suite bit-for-bit, and every variant must sum
 * to the identical {@code n*(n-1)/2} — traversal order changes cost,
 * never the total.
 */
class CacheLocalityFixturesTest {

    @Test
    void randomPermutationMatchesTheFixture() {
        int[] perm = CacheLocalityFixtures.randomPermutation(42L, 1_000_000);
        assertArrayEquals(new int[] {712069, 917377, 307023, 717218, 274070}, java.util.Arrays.copyOfRange(perm, 0, 5));
        assertEquals(-4372133131104027544L, CacheLocalityFixtures.checksum(perm));
        assertEquals(499999500000L, CacheLocalityFixtures.sumPermuted(perm));
    }

    @Test
    void sattoloNextMatchesTheFixture() {
        long[] next = CacheLocalityFixtures.sattoloNext(42L, 1_000_000);
        assertArrayEquals(new long[] {916089, 366385, 207944, 432012, 867535}, java.util.Arrays.copyOfRange(next, 0, 5));
        assertEquals(-6982732617436105870L, CacheLocalityFixtures.traversalChecksum(next));
        assertEquals(499999500000L, CacheLocalityFixtures.sumPointerChase(next));
    }

    @Test
    void sattoloNextIsASingleCycleVisitingEveryElementExactlyOnce() {
        long[] next = CacheLocalityFixtures.sattoloNext(42L, 1_000_000);
        boolean[] seen = new boolean[1_000_000];
        long idx = 0;
        for (int step = 0; step < 1_000_000; step++) {
            idx = next[(int) idx];
            assertEquals(false, seen[(int) idx], "index " + idx + " visited twice before the cycle closed");
            seen[(int) idx] = true;
        }
        assertEquals(0L, idx, "the cycle must return to the start after exactly n steps");
    }

    @Test
    void sequentialSumMatchesTheFixture() {
        assertEquals(499999500000L, CacheLocalityFixtures.sumSequential(1_000_000));
    }

    @Test
    void blockedTraversalMatchesTheFixture() {
        assertEquals(499999500000L, CacheLocalityFixtures.sumBlocked(1_000, 32));
    }

    @Test
    void allFourVariantsAgreeOnTheTotal() {
        int n = 1_000_000;
        long expected = CacheLocalityFixtures.expectedTotal(n);
        assertEquals(499999500000L, expected);

        assertEquals(expected, CacheLocalityFixtures.sumSequential(n));
        assertEquals(expected, CacheLocalityFixtures.sumPermuted(CacheLocalityFixtures.randomPermutation(42L, n)));
        assertEquals(expected, CacheLocalityFixtures.sumPointerChase(CacheLocalityFixtures.sattoloNext(42L, n)));
        assertEquals(expected, CacheLocalityFixtures.sumBlocked(1_000, 32));
    }
}
