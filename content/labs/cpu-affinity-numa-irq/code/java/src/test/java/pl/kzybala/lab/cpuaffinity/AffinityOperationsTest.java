package pl.kzybala.lab.cpuaffinity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AffinityOperationsTest {

    private static final Topology TOPOLOGY = Topology.detect();

    @Test
    void checksumsArePureAndDeterministic() {
        assertEquals(Workloads.spscHandoffChecksum(5000), Workloads.spscHandoffChecksum(5000));
        assertEquals(Workloads.memoryScanChecksum(200_000, 1), Workloads.memoryScanChecksum(200_000, 1));
        assertEquals(Workloads.udpIngestChecksum(2000, 128), Workloads.udpIngestChecksum(2000, 128));
    }

    @ParameterizedTest
    @EnumSource(PlacementTarget.class)
    void spscHandoffChecksumIndependentOfPlacement(PlacementTarget target) throws InterruptedException {
        PlacementResult result = PlacementRunner.run(target, TOPOLOGY,
                () -> Workloads.spscHandoffChecksum(AffinityFixtures.SPSC_HANDOFF_COUNT));
        assertEquals(AffinityFixtures.expectedSpscHandoffChecksum(), result.checksum());
    }

    @ParameterizedTest
    @EnumSource(PlacementTarget.class)
    void memoryScanChecksumIndependentOfPlacement(PlacementTarget target) throws InterruptedException {
        PlacementResult result = PlacementRunner.run(target, TOPOLOGY,
                () -> Workloads.memoryScanChecksum(AffinityFixtures.MEMORY_SCAN_SIZE, AffinityFixtures.MEMORY_SCAN_STRIDE));
        assertEquals(AffinityFixtures.expectedMemoryScanChecksum(), result.checksum());
    }

    @ParameterizedTest
    @EnumSource(PlacementTarget.class)
    void udpIngestChecksumIndependentOfPlacement(PlacementTarget target) throws InterruptedException {
        PlacementResult result = PlacementRunner.run(target, TOPOLOGY,
                () -> Workloads.udpIngestChecksum(AffinityFixtures.UDP_PACKET_COUNT, AffinityFixtures.UDP_PACKET_SIZE));
        assertEquals(AffinityFixtures.expectedUdpIngestChecksum(), result.checksum());
    }

    @Test
    void unpinnedBaselineNeverAttemptsToPin() throws InterruptedException {
        PlacementResult result = PlacementRunner.run(PlacementTarget.UNPINNED_BASELINE, TOPOLOGY,
                () -> Workloads.spscHandoffChecksum(100));
        assertEquals(false, result.pinned());
        assertEquals(true, result.cpu().isEmpty());
    }

    @Test
    void topologyDetectionNeverThrowsOnThisHost() {
        // On this repository's macOS development host, CpuAffinity.isSupported() is false and
        // Topology.detect() must degrade to "unsupported" rather than throw — this is the
        // capability-detection contract every placement variant depends on.
        Topology t = Topology.detect();
        if (!CpuAffinity.isSupported()) {
            assertEquals(false, t.supported());
        }
    }
}
