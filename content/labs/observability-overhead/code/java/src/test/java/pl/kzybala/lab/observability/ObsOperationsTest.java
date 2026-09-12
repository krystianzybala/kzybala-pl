package pl.kzybala.lab.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObsOperationsTest {

    static Stream<Supplier<ObsParams>> datasets() {
        return Stream.of(ObsFixtures::singleEvent, ObsFixtures::errorBurst, ObsFixtures::highCardinalityKey, ObsFixtures::stackTracePath);
    }

    private static long expectedChecksum(ObsParams params) {
        long checksum = 0;
        for (int id : params.ids()) {
            checksum ^= HotPath.work(id);
        }
        return checksum;
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void checksumIndependentOfVariantOnErrorBurst(Variant variant) {
        ObsParams params = ObsFixtures.errorBurst();
        assertEquals(expectedChecksum(params), ObservabilityRunner.run(variant, params).checksum());
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void checksumIndependentOfVariantOnStackTracePath(Variant variant) {
        ObsParams params = ObsFixtures.stackTracePath();
        assertEquals(expectedChecksum(params), ObservabilityRunner.run(variant, params).checksum());
    }

    @Test
    void disabledEagerLoggingStillPaysFormatCost() {
        ObsParams params = ObsFixtures.errorBurst();
        Outcome outcome = ObservabilityRunner.run(Variant.DISABLED_EAGER_LOGGING, params);
        assertEquals(params.ids().length, outcome.formatCount());
        assertEquals(0, outcome.loggedCount());
    }

    @Test
    void disabledLazyLoggingAvoidsFormatCost() {
        ObsParams params = ObsFixtures.errorBurst();
        Outcome outcome = ObservabilityRunner.run(Variant.DISABLED_LAZY_LOGGING, params);
        assertEquals(0, outcome.formatCount());
        assertEquals(0, outcome.loggedCount());
    }

    @Test
    void synchronousLoggingNeverDrops() {
        ObsParams params = ObsFixtures.errorBurst();
        Outcome outcome = ObservabilityRunner.run(Variant.SYNCHRONOUS_LOGGING, params);
        assertEquals(params.ids().length, outcome.loggedCount());
        assertEquals(0, outcome.droppedCount());
    }

    @Test
    void asyncBoundedLoggingDropsExactlyTheOverflow() {
        ObsParams params = ObsFixtures.errorBurst();
        Outcome outcome = ObservabilityRunner.run(Variant.ASYNC_BOUNDED_LOGGING, params);
        assertEquals(params.asyncQueueCapacity(), outcome.loggedCount());
        assertEquals(params.ids().length - params.asyncQueueCapacity(), outcome.droppedCount());
    }

    @Test
    void metricsLabelsTracksExactDistinctKeyCount() {
        ObsParams params = ObsFixtures.highCardinalityKey();
        Outcome outcome = ObservabilityRunner.run(Variant.METRICS_LABELS, params);
        assertEquals(Math.min(params.distinctKeyCount(), params.ids().length), outcome.distinctLabelsSeen());
        assertEquals(params.ids().length, outcome.labelHitsTotal());
    }

    @Test
    void sampledTracingCapturesExactlyEveryNth() {
        ObsParams params = ObsFixtures.stackTracePath();
        Outcome outcome = ObservabilityRunner.run(Variant.SAMPLED_TRACING, params);
        long expected = 0;
        for (int id : params.ids()) {
            if (id % params.sampleRate() == 0) expected++;
        }
        assertEquals(expected, outcome.sampledCount());
    }

    @Test
    void noInstrumentationHasNoSideEffects() {
        Outcome outcome = ObservabilityRunner.run(Variant.NO_INSTRUMENTATION, ObsFixtures.errorBurst());
        assertEquals(0, outcome.formatCount());
        assertEquals(0, outcome.loggedCount());
        assertEquals(0, outcome.droppedCount());
        assertEquals(0, outcome.sampledCount());
    }
}
