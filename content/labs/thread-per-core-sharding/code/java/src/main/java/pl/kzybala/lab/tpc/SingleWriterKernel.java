package pl.kzybala.lab.tpc;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * "Shared-nothing", ownership-partitioned design: each of
 * {@link TpcFixtures#SHARD_COUNT} shards is owned by exactly one worker
 * thread, and only that thread ever writes that shard's counters — no
 * lock is needed on the counters themselves, only on the inbox queue
 * that routes a request to its owning shard (and that queue is a
 * multi-producer/single-consumer structure by construction, the same
 * shape this lab's prerequisite MPSC lab covers). This is the mechanism
 * this lab's hypothesis is actually about: synchronization moves from
 * "every write" to "every routing decision."
 */
public final class SingleWriterKernel {
    private SingleWriterKernel() {}

    public static TpcResult run(WorkloadPlan plan) throws InterruptedException {
        long[] counters = new long[TpcFixtures.KEY_COUNT];
        ConcurrentLinkedQueue<Integer>[] inboxes = new ConcurrentLinkedQueue[TpcFixtures.SHARD_COUNT];
        long[] expectedPerShard = new long[TpcFixtures.SHARD_COUNT];
        for (int s = 0; s < inboxes.length; s++) inboxes[s] = new ConcurrentLinkedQueue<>();
        for (int key : plan.keys) expectedPerShard[TpcFixtures.shardFor(key)]++;

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
                        counters[key]++; // safe: only this thread ever writes keys routed to this shard
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
                    inboxes[TpcFixtures.shardFor(key)].add(key);
                }
            });
        }

        for (Thread t : owners) t.start();
        for (Thread t : requesters) t.start();
        for (Thread t : requesters) t.join();
        for (Thread t : owners) t.join();

        return new TpcResult(counters.clone(), 0);
    }
}
