package pl.kzybala.lab.cpuaffinity;

import java.util.OptionalInt;
import java.util.function.LongSupplier;

/**
 * Runs one workload under one placement target on a fresh thread: best-effort pin (never
 * fabricated — {@code pinned} is only true when the kernel's own placement was verified), then
 * the pure checksum. The checksum is identical regardless of whether pinning succeeded, which is
 * the correctness invariant every test in this lab asserts.
 */
public final class PlacementRunner {
    private PlacementRunner() {}

    public static PlacementResult run(PlacementTarget target, Topology topology, LongSupplier workload) throws InterruptedException {
        long[] checksum = new long[1];
        boolean[] pinned = new boolean[1];
        OptionalInt targetCpu = target.resolveCpu(topology);

        Thread worker = new Thread(() -> {
            if (targetCpu.isPresent()) {
                pinned[0] = CpuAffinity.tryPinCurrentThread(targetCpu.getAsInt());
            }
            checksum[0] = workload.getAsLong();
        });
        worker.start();
        worker.join();

        return new PlacementResult(target, pinned[0], targetCpu, checksum[0]);
    }
}
