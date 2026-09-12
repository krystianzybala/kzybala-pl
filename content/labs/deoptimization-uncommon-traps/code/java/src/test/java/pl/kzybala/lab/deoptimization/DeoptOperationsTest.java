package pl.kzybala.lab.deoptimization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/deoptimization-uncommon-traps-fixtures.json). WHEN and how
 * the underlying type/exception/null pattern shifts changes cost, never
 * the fixture-pinned total.
 */
class DeoptOperationsTest {

    private static void assertDataset(String dataset, long[] first5, long stable, long shift, long exception, long lateSubtype, long nullability) {
        long[] inputs = DeoptFixtures.inputsFor(dataset, DeoptFixtures.N);
        assertArrayEquals(first5, java.util.Arrays.copyOfRange(inputs, 0, 5));

        assertEquals(stable, DeoptOperations.sumStableTypeProfile(inputs));
        assertEquals(shift, DeoptOperations.sumProfileShiftAfterWarmup(inputs));
        assertEquals(exception, DeoptOperations.sumRareExceptionPath(inputs));
        assertEquals(lateSubtype, DeoptOperations.sumLateSubtypeLoading(inputs));
        assertEquals(nullability, DeoptOperations.sumNullabilityShift(inputs));
    }

    @Test
    void strategyDispatchAllVariantsMatchTheFixture() {
        assertDataset("strategyDispatch", new long[] {0, 1, 2, 3, 4},
            500000500000L, 875000250000L, 499999799998L, 500121915392L, 499625749500L);
    }

    @Test
    void parsingMixedRecordsAllVariantsMatchTheFixture() {
        assertDataset("parsingMixedRecords", new long[] {963762, 958315, 339298, 9905, 79709},
            500317225994L, 750461615780L, 500316711341L, 500445453298L, 500058690181L);
    }

    @Test
    void rareValidationFailureAllVariantsMatchTheFixture() {
        assertDataset("rareValidationFailure", new long[] {971379, 504874, 867083, 317012, 469816},
            500280750092L, 750435887722L, 500280423156L, 500397852152L, 500030587273L);
    }

    @Test
    void typesComputeExpectedTransforms() {
        assertEquals(43L, new TypeA().apply(42L));
        assertEquals(126L, new TypeB().apply(42L));
        assertEquals(42L ^ 0x5A5AL, new TypeC().apply(42L));
    }
}
