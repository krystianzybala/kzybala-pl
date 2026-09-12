package pl.kzybala.lab.tpc;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntUnaryOperator;

/**
 * A deliberately simplified "stop-the-world" rebalance: the workload runs
 * in two half-sized phases against the single-writer mechanism, with a
 * different key-to-shard mapping in each phase. Between phases, every
 * phase-1 owner thread is fully joined (a real quiescence point) before
 * any phase-2 owner thread starts — this is what makes the design safe
 * without extra synchronization on the counters array (the {@code join()}
 * calls are the happens-before edge), but it also means this simulation
 * measures a full-pause rebalance, not a live migration that keeps
 * serving requests during the handoff. That simplification is
 * deliberate and disclosed (see theory.md's Assumptions and scope), not
 * a claim about how a production rebalance would work.
 */
public final class RebalanceSimulationKernel {
    private RebalanceSimulationKernel() {}

    private static void runPhase(WorkloadPlan plan, IntUnaryOperator shardOf, long[] counters) throws InterruptedException {
        ConcurrentLinkedQueue<Integer>[] inboxes = new ConcurrentLinkedQueue[TpcFixtures.SHARD_COUNT];
        long[] expectedPerShard = new long[TpcFixtures.SHARD_COUNT];
        for (int s = 0; s < inboxes.length; s++) inboxes[s] = new ConcurrentLinkedQueue<>();
        for (int key : plan.keys) expectedPerShard[shardOf.applyAsInt(key)]++;

        Thread[] owners = new Thread[TpcFixtures.SHARD_COUNT];
        for (int s = 0; s < owners.length; s++) {
            final int shardId = s;
            final long expected = expectedPerShard[s];
            owners[s] = new Thread(() -> {
                long processed = 0;
                long spins = 0;
                while (processed < expected) {
                    Integer key = inboxes[shardId].poll();
                    if (key != null) {
                        counters[key]++;
                        processed++;
                        spins = 0;
                    } else if (++spins >= TpcFixtures.MAX_SPIN_ITERATIONS) {
                        throw new IllegalStateException(
                                "shard " + shardId + " spun out waiting for requests, processed " + processed + "/" + expected);
                    }
                }
            });
        }

        Thread[] requesters = new Thread[TpcFixtures.REQUESTER_THREADS];
        for (int r = 0; r < requesters.length; r++) {
            int[] chunk = plan.chunkFor(r, requesters.length);
            requesters[r] = new Thread(() -> {
                for (int key : chunk) {
                    inboxes[shardOf.applyAsInt(key)].add(key);
                }
            });
        }

        for (Thread t : owners) t.start();
        for (Thread t : requesters) t.start();
        for (Thread t : requesters) t.join();
        for (Thread t : owners) t.join(); // full quiescence — the happens-before edge the next phase relies on
    }

    public static TpcResult run() throws InterruptedException {
        long[] counters = new long[TpcFixtures.KEY_COUNT];
        int perPhase = TpcFixtures.REQUESTS_PER_KEY_UNIFORM / 2;

        IntUnaryOperator initialMapping = TpcFixtures::shardFor;
        runPhase(WorkloadPlan.uniformFraction(perPhase), initialMapping, counters);

        long rebalanceStart = System.nanoTime();
        // The "rebalance" itself: key 0 moves from its default shard to
        // the next shard over. In a real system this step would migrate
        // any per-shard local state for the moved key(s) and update a
        // routing table; here, with full quiescence already established
        // by runPhase's joins, there is no per-key state to migrate
        // beyond the counters array itself (already safely visible), so
        // this measures the pause's own bookkeeping cost, not a realistic
        // migration cost — see theory.md.
        IntUnaryOperator rebalancedMapping = key ->
                key == 0 ? (TpcFixtures.shardFor(0) + 1) % TpcFixtures.SHARD_COUNT : TpcFixtures.shardFor(key);
        long rebalanceCostNanos = System.nanoTime() - rebalanceStart;

        runPhase(WorkloadPlan.uniformFraction(perPhase), rebalancedMapping, counters);

        return new TpcResult(counters, rebalanceCostNanos);
    }
}
