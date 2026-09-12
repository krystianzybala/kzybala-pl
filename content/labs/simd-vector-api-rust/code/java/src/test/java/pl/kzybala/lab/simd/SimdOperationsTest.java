package pl.kzybala.lab.simd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/simd-vector-api-rust-fixtures.json). All five variants
 * must agree for a given dataset — vectorization strategy changes
 * ns/element and vector instructions, never the result.
 * {@code misalignedInput} and {@code smallTailHeavyInput} reuse
 * {@code explicitSimd} over a different input shape (offset by one
 * element; truncated to 17 elements) and are checked against the SAME
 * kernel's scalar result over the identical shape.
 */
class SimdOperationsTest {

    @Test
    void sumMinMaxAllVariantsAgree() {
        int[] value = SimdFixtures.generateSumMinMax(SimdFixtures.SUM_MIN_MAX_N);
        assertEquals(371320, value[0]);

        SumMinMaxKernel.Result expected = SumMinMaxKernel.scalarBaseline(value, 0, value.length);
        assertEquals(500_149_865_068L, expected.sum());
        assertEquals(0, expected.min());
        assertEquals(999999, expected.max());

        assertEquals(expected, SumMinMaxKernel.autoVectorizedCandidate(value, 0, value.length));
        assertEquals(expected, SumMinMaxKernel.explicitSimd(value, 0, value.length));

        // misalignedInput: same kernel, offset by one element
        SumMinMaxKernel.Result expectedOffset = SumMinMaxKernel.scalarBaseline(value, 1, value.length - 1);
        assertEquals(expectedOffset, SumMinMaxKernel.explicitSimd(value, 1, value.length - 1));

        // smallTailHeavyInput: same kernel, truncated to 17 elements
        SumMinMaxKernel.Result expectedSmall = SumMinMaxKernel.scalarBaseline(value, 0, SimdFixtures.SMALL_TAIL_N);
        assertEquals(expectedSmall, SumMinMaxKernel.explicitSimd(value, 0, SimdFixtures.SMALL_TAIL_N));
    }

    @Test
    void thresholdFilterAllVariantsAgree() {
        int[] value = SimdFixtures.generateThreshold(SimdFixtures.THRESHOLD_N);
        assertEquals(601593, value[0]);

        long expected = ThresholdFilterKernel.scalarBaseline(value, 0, value.length, SimdFixtures.THRESHOLD);
        assertEquals(501050L, expected);

        assertEquals(expected, ThresholdFilterKernel.autoVectorizedCandidate(value, 0, value.length, SimdFixtures.THRESHOLD));
        assertEquals(expected, ThresholdFilterKernel.explicitSimd(value, 0, value.length, SimdFixtures.THRESHOLD));

        long expectedOffset = ThresholdFilterKernel.scalarBaseline(value, 1, value.length - 1, SimdFixtures.THRESHOLD);
        assertEquals(expectedOffset, ThresholdFilterKernel.explicitSimd(value, 1, value.length - 1, SimdFixtures.THRESHOLD));

        long expectedSmall = ThresholdFilterKernel.scalarBaseline(value, 0, SimdFixtures.SMALL_TAIL_N, SimdFixtures.THRESHOLD);
        assertEquals(expectedSmall, ThresholdFilterKernel.explicitSimd(value, 0, SimdFixtures.SMALL_TAIL_N, SimdFixtures.THRESHOLD));
    }

    @Test
    void dotProductAllVariantsAgree() {
        SimdFixtures.DotProductSource s = SimdFixtures.generateDotProduct(SimdFixtures.DOT_PRODUCT_N);
        assertEquals(554.0, s.a[0]);
        assertEquals(827.0, s.b[0]);

        double expected = DotProductKernel.scalarBaseline(s.a, s.b, 0, s.a.length);
        assertEquals(249_655_062_671.0, expected);

        assertEquals(expected, DotProductKernel.autoVectorizedCandidate(s.a, s.b, 0, s.a.length));
        assertEquals(expected, DotProductKernel.explicitSimd(s.a, s.b, 0, s.a.length));

        double expectedOffset = DotProductKernel.scalarBaseline(s.a, s.b, 1, s.a.length - 1);
        assertEquals(expectedOffset, DotProductKernel.explicitSimd(s.a, s.b, 1, s.a.length - 1));

        double expectedSmall = DotProductKernel.scalarBaseline(s.a, s.b, 0, SimdFixtures.SMALL_TAIL_N);
        assertEquals(expectedSmall, DotProductKernel.explicitSimd(s.a, s.b, 0, SimdFixtures.SMALL_TAIL_N));
    }

    @Test
    void byteClassificationAllVariantsAgree() {
        byte[] value = SimdFixtures.generateByteClassification(SimdFixtures.BYTE_CLASSIFICATION_N);
        assertEquals((byte) 124, value[0]);

        long expected = ByteClassificationKernel.scalarBaseline(value, 0, value.length);
        assertEquals(741505L, expected);

        assertEquals(expected, ByteClassificationKernel.autoVectorizedCandidate(value, 0, value.length));
        assertEquals(expected, ByteClassificationKernel.explicitSimd(value, 0, value.length));

        long expectedOffset = ByteClassificationKernel.scalarBaseline(value, 1, value.length - 1);
        assertEquals(expectedOffset, ByteClassificationKernel.explicitSimd(value, 1, value.length - 1));

        long expectedSmall = ByteClassificationKernel.scalarBaseline(value, 0, SimdFixtures.SMALL_TAIL_N);
        assertEquals(expectedSmall, ByteClassificationKernel.explicitSimd(value, 0, SimdFixtures.SMALL_TAIL_N));
    }
}
