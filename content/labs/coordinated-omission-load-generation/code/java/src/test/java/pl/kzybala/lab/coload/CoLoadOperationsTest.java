package pl.kzybala.lab.coload;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoLoadOperationsTest {

    @ParameterizedTest
    @EnumSource(Variant.class)
    void admissionInvariantHoldsOnPeriodicStall(Variant variant) {
        Outcome outcome = LoadGenSimulator.simulate(variant, CoLoadFixtures.periodicTenMsStall());
        assertTrue(outcome.admissionInvariantHolds(), outcome.toString());
    }

    @ParameterizedTest
    @EnumSource(Variant.class)
    void admissionInvariantHoldsOnBoundedOverload(Variant variant) {
        Outcome outcome = LoadGenSimulator.simulate(variant, CoLoadFixtures.boundedServerOverload());
        assertTrue(outcome.admissionInvariantHolds(), outcome.toString());
    }

    @org.junit.jupiter.api.Test
    void closedLoopResponseTimeAlwaysEqualsServiceTime() {
        Outcome outcome = LoadGenSimulator.simulate(Variant.CLOSED_LOOP, CoLoadFixtures.periodicTenMsStall());
        assertEquals(outcome.totalServiceTimeNanos(), outcome.totalResponseTimeNanos(),
                "closed loop must hide queueing entirely: response time == service time, always");
        assertEquals(0, outcome.missed());
    }

    @org.junit.jupiter.api.Test
    void closedLoopMaxResponseTimeEqualsStallMagnitude() {
        Outcome outcome = LoadGenSimulator.simulate(Variant.CLOSED_LOOP, CoLoadFixtures.periodicTenMsStall());
        assertEquals(10_000_000L, outcome.maxResponseTimeNanos());
    }

    @org.junit.jupiter.api.Test
    void omissionCorrectedRecordsMoreSamplesThanProducedUnderStall() {
        Outcome outcome = LoadGenSimulator.simulate(Variant.OMISSION_CORRECTED_RECORDING, CoLoadFixtures.periodicTenMsStall());
        assertEquals(0, outcome.missed());
        assertTrue(outcome.recordedSamples() > outcome.produced(),
                "correction must add synthetic samples for the requests the stall silently skipped");
    }

    @org.junit.jupiter.api.Test
    void openLoopFixedRateMissesUnderSustainedOverload() {
        Outcome outcome = LoadGenSimulator.simulate(Variant.OPEN_LOOP_FIXED_RATE, CoLoadFixtures.boundedServerOverload());
        assertTrue(outcome.missed() > 0, "a persistently overloaded server with bounded capacity must eventually miss schedules");
    }

    @org.junit.jupiter.api.Test
    void openLoopNeverMissesUnderLightLoad() {
        Outcome outcome = LoadGenSimulator.simulate(Variant.OPEN_LOOP_FIXED_RATE, CoLoadFixtures.periodicTenMsStall());
        assertEquals(0, outcome.missed());
    }

    @org.junit.jupiter.api.Test
    void burstScheduleGroupsIntendedSendTimesByBurstSize() {
        // Correctness proxy: admitted + missed still equals produced even when many requests share one intended send time.
        Outcome outcome = LoadGenSimulator.simulate(Variant.BURST_SCHEDULE, CoLoadFixtures.periodicTenMsStall());
        assertTrue(outcome.admissionInvariantHolds());
    }
}
