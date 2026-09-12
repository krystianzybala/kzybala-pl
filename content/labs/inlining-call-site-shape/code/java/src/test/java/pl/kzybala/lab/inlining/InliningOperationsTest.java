package pl.kzybala.lab.inlining;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Correctness gate against the shared fixture
 * (../fixtures/inlining-call-site-shape-fixtures.json). Two pairwise
 * equalities are the oracle: monomorphic == oversizedCallee (same single
 * strategy, different code size) and megamorphic == switchDispatch (same
 * six-way round-robin, different dispatch mechanism) — dispatch
 * mechanism and code size change cost, never the result.
 */
class InliningOperationsTest {

    private static void assertDataset(String dataset, long[] first5, long monoAndOversized, long bimorphic, long megaAndSwitch) {
        long[] inputs = InliningFixtures.inputsFor(dataset, InliningFixtures.N);
        assertArrayEquals(first5, java.util.Arrays.copyOfRange(inputs, 0, 5));

        long mono = InliningOperations.sumMonomorphic(inputs);
        long oversized = InliningOperations.sumOversizedCallee(inputs);
        assertEquals(monoAndOversized, mono);
        assertEquals(mono, oversized, "monomorphic and oversizedCallee must agree — same logical strategy");

        assertEquals(bimorphic, InliningOperations.sumBimorphic(inputs));

        long mega = InliningOperations.sumMegamorphic(inputs);
        long viaSwitch = InliningOperations.sumSwitchDispatch(inputs);
        assertEquals(megaAndSwitch, mega);
        assertEquals(mega, viaSwitch, "megamorphic and switchDispatch must agree — same assignment, different mechanism");
    }

    @Test
    void pricingFunctionsAllVariantsAgree() {
        assertDataset("pricingFunctions", new long[] {0, 1, 2, 3, 4}, 500000500000L, 1000000000000L, 687543385141L);
    }

    @Test
    void codecStrategiesAllVariantsAgree() {
        assertDataset("codecStrategies", new long[] {805674, 905471, 320954, 629736, 84162}, 499866710459L, 999276779457L, 687169279832L);
    }

    @Test
    void validationRulesAllVariantsAgree() {
        assertDataset("validationRules", new long[] {75435, 585150, 59539, 476429, 785191}, 499956129091L, 999456305265L, 687433423258L);
    }

    @Test
    void strategyPoolHasSixDistinctConcreteTypes() {
        LongStrategy[] pool = LongStrategy.pool();
        assertEquals(6, pool.length);
        long distinctClasses = java.util.Arrays.stream(pool).map(Object::getClass).distinct().count();
        assertEquals(6, distinctClasses, "the megamorphic variant requires six genuinely distinct concrete types");
    }

    @Test
    void strategyKindMatchesLongStrategyForEveryIndex() {
        LongStrategy[] pool = LongStrategy.pool();
        for (int i = 0; i < 6; i++) {
            for (long x : new long[] {0, 1, 999_999, 123_456}) {
                assertEquals(pool[i].apply(x), StrategyKind.forIndex(i).apply(x));
            }
        }
    }
}
