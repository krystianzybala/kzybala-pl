package pl.kzybala.lab.aosvssoa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/aos-vs-soa-fixtures.json). Every value here must match the
 * Rust suite bit-for-bit, and every layout of a given dataset must
 * reproduce the identical {@code hotSum} and cold checksum — layout
 * changes storage, never the data.
 */
class AosVsSoaFixturesTest {

    @Test
    void marketQuotesGenerationMatchesTheFixture() {
        AosVsSoaFixtures.Generated g = AosVsSoaFixtures.generate(4, 42L, 1_000_000);
        assertEquals(-8491869883421422093L, AosVsSoaFixtures.checksum(g.hotA));
        assertEquals(1964254207014998244L, AosVsSoaFixtures.checksum(g.hotB));
        assertArrayEquals(new long[] {805674, 29075, 418483, 94591, 868695}, java.util.Arrays.copyOfRange(g.hotA, 0, 5));
        assertArrayEquals(new long[] {905471, 704114, 552213, 125452, 853729}, java.util.Arrays.copyOfRange(g.hotB, 0, 5));
        assertEquals(-2526446158705005686L, AosVsSoaFixtures.checksumCold(g.cold));
        assertArrayEquals(new long[] {320954, 629736, 84162, 275427}, g.cold[0]);
        assertEquals(999610704015L, AosVsSoaFixtures.expectedHotSum(g.hotA, g.hotB));
    }

    @Test
    void positionsGenerationMatchesTheFixture() {
        AosVsSoaFixtures.Generated g = AosVsSoaFixtures.generate(8, 42L, 1_000_000);
        assertEquals(-6590571356256859184L, AosVsSoaFixtures.checksum(g.hotA));
        assertEquals(458225929394024333L, AosVsSoaFixtures.checksum(g.hotB));
        assertEquals(-3846297679626790457L, AosVsSoaFixtures.checksumCold(g.cold));
        assertEquals(999893110961L, AosVsSoaFixtures.expectedHotSum(g.hotA, g.hotB));
    }

    @Test
    void spatialPointsGenerationMatchesTheFixture() {
        AosVsSoaFixtures.Generated g = AosVsSoaFixtures.generate(2, 42L, 1_000_000);
        assertEquals(9215335623524743799L, AosVsSoaFixtures.checksum(g.hotA));
        assertEquals(-4924883481048905347L, AosVsSoaFixtures.checksum(g.hotB));
        assertEquals(3239977018108460599L, AosVsSoaFixtures.checksumCold(g.cold));
        assertEquals(1000104865222L, AosVsSoaFixtures.expectedHotSum(g.hotA, g.hotB));
    }

    @Test
    void allFourLayoutsAgreeOnHotSumAndColdChecksumForMarketQuotes() {
        AosVsSoaFixtures.Generated g = AosVsSoaFixtures.generate(4, 42L, 1_000_000);
        long expectedHotSum = AosVsSoaFixtures.expectedHotSum(g.hotA, g.hotB);
        long expectedColdChecksum = AosVsSoaFixtures.checksumCold(g.cold);

        AosHeapLayout heap = AosHeapLayout.of(g);
        assertEquals(expectedHotSum, heap.sumHot());
        assertEquals(expectedColdChecksum, heap.coldChecksum());

        SoaLayout soa = SoaLayout.of(g, 4);
        assertEquals(expectedHotSum, soa.sumHot());
        assertEquals(expectedColdChecksum, soa.coldChecksum());

        HybridLayout hybrid = HybridLayout.of(g, 4);
        assertEquals(expectedHotSum, hybrid.sumHot());
        assertEquals(expectedColdChecksum, hybrid.coldChecksum());

        try (AosPackedLayout packed = AosPackedLayout.of(g, 4)) {
            assertEquals(expectedHotSum, packed.sumHot());
            assertEquals(expectedColdChecksum, packed.coldChecksum());
            assertEquals(48L, packed.recordStrideBytes());
        }
    }

    @Test
    void allFourLayoutsAgreeForPositionsAndSpatialPoints() {
        for (int coldWords : new int[] {8, 2}) {
            AosVsSoaFixtures.Generated g = AosVsSoaFixtures.generate(coldWords, 42L, 1_000_000);
            long expectedHotSum = AosVsSoaFixtures.expectedHotSum(g.hotA, g.hotB);
            long expectedColdChecksum = AosVsSoaFixtures.checksumCold(g.cold);

            assertEquals(expectedHotSum, AosHeapLayout.of(g).sumHot());
            assertEquals(expectedHotSum, SoaLayout.of(g, coldWords).sumHot());
            assertEquals(expectedHotSum, HybridLayout.of(g, coldWords).sumHot());
            assertEquals(expectedColdChecksum, SoaLayout.of(g, coldWords).coldChecksum());
            assertEquals(expectedColdChecksum, HybridLayout.of(g, coldWords).coldChecksum());

            try (AosPackedLayout packed = AosPackedLayout.of(g, coldWords)) {
                assertEquals(expectedHotSum, packed.sumHot());
                assertEquals(expectedColdChecksum, packed.coldChecksum());
                assertEquals(16L + coldWords * 8L, packed.recordStrideBytes());
            }
        }
    }
}
