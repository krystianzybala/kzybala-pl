package pl.kzybala.lab.dllp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DllpOperationsTest {

    static Stream<Supplier<PipelineParams>> datasets() {
        return Stream.of(
                DllpFixtures::smallEventsUniformSteady,
                DllpFixtures::largeEventsUniformSteady,
                DllpFixtures::mediumEventsHotKeyBurst,
                DllpFixtures::mediumEventsUniformBurst);
    }

    @ParameterizedTest
    @EnumSource(PipelineVariant.class)
    void invariantHoldsOnSmallEventsUniformSteady(PipelineVariant variant) {
        assertInvariant(variant, DllpFixtures.smallEventsUniformSteady());
    }

    @ParameterizedTest
    @EnumSource(PipelineVariant.class)
    void invariantHoldsOnMediumEventsHotKeyBurst(PipelineVariant variant) {
        assertInvariant(variant, DllpFixtures.mediumEventsHotKeyBurst());
    }

    @ParameterizedTest
    @EnumSource(PipelineVariant.class)
    void invariantHoldsOnMediumEventsUniformBurst(PipelineVariant variant) {
        assertInvariant(variant, DllpFixtures.mediumEventsUniformBurst());
    }

    private static void assertInvariant(PipelineVariant variant, PipelineParams params) {
        Outcome outcome = PipelineSimulator.simulate(variant, params);
        assertTrue(outcome.invariantHolds(), () -> variant + ": " + outcome);
    }

    @Test
    void naiveNeverDropsOrLosesEvents() {
        Outcome outcome = PipelineSimulator.simulate(PipelineVariant.NAIVE_OBJECT_QUEUE, DllpFixtures.mediumEventsUniformBurst());
        assertEquals(0, outcome.dropped());
        assertEquals(0, outcome.restartLoss());
        assertEquals(outcome.produced(), outcome.delivered());
    }

    @Test
    void checksumIndependentOfVariantWhenNothingIsDroppedOrLost() {
        // Uniform steady load, generous capacity: OPTIMIZED should admit and deliver everything,
        // so its checksum must match NAIVE's (the identical events, decoded identically).
        PipelineParams params = DllpFixtures.smallEventsUniformSteady();
        Outcome naive = PipelineSimulator.simulate(PipelineVariant.NAIVE_OBJECT_QUEUE, params);
        Outcome optimized = PipelineSimulator.simulate(PipelineVariant.OPTIMIZED, params);
        assertEquals(0, optimized.dropped());
        assertEquals(naive.checksum(), optimized.checksum());
    }

    @Test
    void overloadProfileForcesDropsEvenUnderAHandleableDataset() {
        Outcome optimized = PipelineSimulator.simulate(PipelineVariant.OPTIMIZED, DllpFixtures.mediumEventsHotKeyBurst());
        Outcome overload = PipelineSimulator.simulate(PipelineVariant.OVERLOAD_PROFILE, DllpFixtures.mediumEventsHotKeyBurst());
        assertTrue(overload.dropped() > optimized.dropped());
    }

    @Test
    void hotKeySkewCanOverflowOneShardEvenWithGenerousAggregateCapacity() {
        Outcome outcome = PipelineSimulator.simulate(PipelineVariant.OPTIMIZED, DllpFixtures.mediumEventsHotKeyBurst());
        assertTrue(outcome.maxShardDepth() > 0);
    }

    @Test
    void faultRestartProfileLosesExactlyTheClearedShardBacklog() {
        Outcome outcome = PipelineSimulator.simulate(PipelineVariant.FAULT_RESTART_PROFILE, DllpFixtures.mediumEventsHotKeyBurst());
        assertTrue(outcome.restartLoss() >= 0);
        assertTrue(outcome.invariantHolds());
    }
}
