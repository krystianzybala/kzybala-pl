package pl.kzybala.lab.cpuaffinity;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class AffinityBenchmark {

    private Topology topology;

    @Setup(Level.Trial)
    public void setup() {
        topology = Topology.detect();
    }

    @Benchmark
    public PlacementResult unpinnedMemoryScan() throws InterruptedException {
        return PlacementRunner.run(PlacementTarget.UNPINNED_BASELINE, topology,
                () -> Workloads.memoryScanChecksum(AffinityFixtures.MEMORY_SCAN_SIZE, AffinityFixtures.MEMORY_SCAN_STRIDE));
    }

    @Benchmark
    public PlacementResult pinnedIsolatedCoreMemoryScan() throws InterruptedException {
        return PlacementRunner.run(PlacementTarget.PINNED_ISOLATED_CORE, topology,
                () -> Workloads.memoryScanChecksum(AffinityFixtures.MEMORY_SCAN_SIZE, AffinityFixtures.MEMORY_SCAN_STRIDE));
    }

    @Benchmark
    public PlacementResult smtSiblingsSpscHandoff() throws InterruptedException {
        return PlacementRunner.run(PlacementTarget.SMT_SIBLINGS, topology,
                () -> Workloads.spscHandoffChecksum(AffinityFixtures.SPSC_HANDOFF_COUNT));
    }

    @Benchmark
    public PlacementResult sameNumaNodeUdpIngest() throws InterruptedException {
        return PlacementRunner.run(PlacementTarget.SAME_NUMA_NODE, topology,
                () -> Workloads.udpIngestChecksum(AffinityFixtures.UDP_PACKET_COUNT, AffinityFixtures.UDP_PACKET_SIZE));
    }

    @Benchmark
    public PlacementResult remoteNumaNodeUdpIngest() throws InterruptedException {
        return PlacementRunner.run(PlacementTarget.REMOTE_NUMA_NODE, topology,
                () -> Workloads.udpIngestChecksum(AffinityFixtures.UDP_PACKET_COUNT, AffinityFixtures.UDP_PACKET_SIZE));
    }
}
