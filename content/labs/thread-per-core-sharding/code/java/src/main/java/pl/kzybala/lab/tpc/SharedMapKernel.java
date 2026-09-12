package pl.kzybala.lab.tpc;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * The naive baseline: no sharding at all. Every requester thread updates
 * any key directly against one shared {@link AtomicLongArray} — this is
 * the "shared concurrent map" variant, modeled as a flat array of atomic
 * per-key counters (the update-in-place shape a real concurrent map
 * would offer via, e.g., {@code compute}/{@code merge}) rather than a
 * literal {@code ConcurrentHashMap}, so the measured cost is the
 * per-key atomic contention itself, not incidental hash-map bucket
 * mechanics.
 */
public final class SharedMapKernel {
    private SharedMapKernel() {}

    public static TpcResult run(WorkloadPlan plan) throws InterruptedException {
        AtomicLongArray counters = new AtomicLongArray(TpcFixtures.KEY_COUNT);

        Thread[] requesters = new Thread[TpcFixtures.REQUESTER_THREADS];
        for (int r = 0; r < requesters.length; r++) {
            int[] chunk = plan.chunkFor(r, requesters.length);
            requesters[r] = new Thread(() -> {
                for (int key : chunk) {
                    counters.incrementAndGet(key);
                }
            });
        }

        for (Thread t : requesters) t.start();
        for (Thread t : requesters) t.join();

        long[] finalCounters = new long[TpcFixtures.KEY_COUNT];
        for (int k = 0; k < finalCounters.length; k++) finalCounters[k] = counters.get(k);
        return new TpcResult(finalCounters, 0);
    }
}
