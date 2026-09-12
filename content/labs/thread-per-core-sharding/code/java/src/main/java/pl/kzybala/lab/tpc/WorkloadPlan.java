package pl.kzybala.lab.tpc;

/**
 * Deterministic key-request sequence for one dataset: a flat array of key
 * ids, one entry per request, built by repeating each key exactly its
 * expected count. Split into {@link TpcFixtures#REQUESTER_THREADS}
 * contiguous chunks so requester threads can submit in parallel; the
 * chunking itself has no effect on correctness (only the per-key totals
 * matter), only on how requests are interleaved across shards in time.
 */
public final class WorkloadPlan {
    public final int[] keys;

    private WorkloadPlan(int[] keys) {
        this.keys = keys;
    }

    public static WorkloadPlan uniform() {
        return build(TpcFixtures::expectedUniform);
    }

    public static WorkloadPlan skewed() {
        return build(TpcFixtures::expectedSkewed);
    }

    /** Every key gets exactly {@code perKeyCount} requests — used by the
     * rebalance simulation to build two half-sized phases that, combined,
     * add up to the full uniform dataset per key. */
    public static WorkloadPlan uniformFraction(int perKeyCount) {
        return build(key -> perKeyCount);
    }

    private interface ExpectedCount {
        int expected(int key);
    }

    private static WorkloadPlan build(ExpectedCount expected) {
        int total = 0;
        for (int k = 0; k < TpcFixtures.KEY_COUNT; k++) total += expected.expected(k);
        int[] keys = new int[total];
        int idx = 0;
        for (int k = 0; k < TpcFixtures.KEY_COUNT; k++) {
            int count = expected.expected(k);
            for (int i = 0; i < count; i++) keys[idx++] = k;
        }
        return new WorkloadPlan(keys);
    }

    /** The [start, end) slice of {@link #keys} assigned to requester
     * thread {@code requesterId} out of {@link TpcFixtures#REQUESTER_THREADS}. */
    public int[] chunkFor(int requesterId, int requesterCount) {
        int total = keys.length;
        int base = total / requesterCount;
        int extra = total % requesterCount;
        int start = requesterId * base + Math.min(requesterId, extra);
        int end = start + base + (requesterId < extra ? 1 : 0);
        int[] chunk = new int[end - start];
        System.arraycopy(keys, start, chunk, 0, chunk.length);
        return chunk;
    }
}
