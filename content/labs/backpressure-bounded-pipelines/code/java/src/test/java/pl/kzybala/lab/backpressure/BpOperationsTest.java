package pl.kzybala.lab.backpressure;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BpOperationsTest {

    static Stream<Supplier<PipelineParams>> datasets() {
        return Stream.of(
                BpFixtures::steadyBelowCapacity,
                BpFixtures::shortBurst,
                BpFixtures::sustainedOverload,
                BpFixtures::hotKeySkew);
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void invariantHoldsOnSteadyBelowCapacity(Policy policy) {
        assertInvariant(policy, BpFixtures.steadyBelowCapacity());
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void invariantHoldsOnShortBurst(Policy policy) {
        assertInvariant(policy, BpFixtures.shortBurst());
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void invariantHoldsOnSustainedOverload(Policy policy) {
        assertInvariant(policy, BpFixtures.sustainedOverload());
    }

    @ParameterizedTest
    @EnumSource(Policy.class)
    void invariantHoldsOnHotKeySkew(Policy policy) {
        assertInvariant(policy, BpFixtures.hotKeySkew());
    }

    private static void assertInvariant(Policy policy, PipelineParams params) {
        SimResult result = PipelineSimulator.simulate(policy, params);
        assertTrue(result.invariantHolds(),
                () -> policy + ": produced=" + result.produced() + " delivered=" + result.delivered()
                        + " rejected=" + result.rejected() + " dropped=" + result.dropped()
                        + " superseded=" + result.superseded());
        if (policy != Policy.UNBOUNDED) {
            assertTrue(result.maxQueueDepth() <= Math.max(params.capacity(), params.keyCount()),
                    () -> policy + ": maxQueueDepth " + result.maxQueueDepth() + " exceeded bound");
        }
    }

    @org.junit.jupiter.api.Test
    void unboundedNeverDropsOrRejects() {
        SimResult result = PipelineSimulator.simulate(Policy.UNBOUNDED, BpFixtures.sustainedOverload());
        assertEquals(0, result.rejected());
        assertEquals(0, result.dropped());
        assertEquals(0, result.superseded());
        assertEquals(result.produced(), result.delivered());
    }

    @org.junit.jupiter.api.Test
    void boundedBlockNeverDropsOrRejectsEitherButStillBounded() {
        SimResult result = PipelineSimulator.simulate(Policy.BOUNDED_BLOCK, BpFixtures.sustainedOverload());
        assertEquals(0, result.rejected());
        assertEquals(0, result.dropped());
        assertEquals(result.produced(), result.delivered());
    }

    @org.junit.jupiter.api.Test
    void boundedRejectActuallyRejectsUnderOverload() {
        SimResult result = PipelineSimulator.simulate(Policy.BOUNDED_REJECT, BpFixtures.sustainedOverload());
        assertTrue(result.rejected() > 0, "expected rejections under sustained overload");
    }

    @org.junit.jupiter.api.Test
    void coalesceByKeySupersedesUnderHotKeySkew() {
        SimResult result = PipelineSimulator.simulate(Policy.COALESCE_BY_KEY, BpFixtures.hotKeySkew());
        assertTrue(result.superseded() > 0, "expected coalesced (superseded) items under hot-key skew");
    }
}
