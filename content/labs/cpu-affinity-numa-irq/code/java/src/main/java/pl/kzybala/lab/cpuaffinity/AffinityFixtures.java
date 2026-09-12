package pl.kzybala.lab.cpuaffinity;

/** Hardcoded mirror of code/fixtures/cpu-affinity-numa-irq-fixtures.json. */
public final class AffinityFixtures {
    private AffinityFixtures() {}

    public static final int SPSC_HANDOFF_COUNT = 5000;
    public static final int MEMORY_SCAN_SIZE = 200_000;
    public static final int MEMORY_SCAN_STRIDE = 1;
    public static final int UDP_PACKET_COUNT = 2000;
    public static final int UDP_PACKET_SIZE = 128;

    public static long expectedSpscHandoffChecksum() {
        return Workloads.spscHandoffChecksum(SPSC_HANDOFF_COUNT);
    }

    public static long expectedMemoryScanChecksum() {
        return Workloads.memoryScanChecksum(MEMORY_SCAN_SIZE, MEMORY_SCAN_STRIDE);
    }

    public static long expectedUdpIngestChecksum() {
        return Workloads.udpIngestChecksum(UDP_PACKET_COUNT, UDP_PACKET_SIZE);
    }
}
