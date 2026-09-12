package pl.kzybala.lab.tpc;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Keys are partitioned into {@link TpcFixtures#SHARD_COUNT} fixed shards
 * ({@code key % SHARD_COUNT}, the same stable routing function every
 * variant in this lab uses), each guarded by its own {@link ReentrantLock}.
 * Any requester thread may update any key, but it must hold that key's
 * shard lock to do so — contention is reduced from "one global point" to
 * "one point per shard," never eliminated.
 */
public final class MutexShardsKernel {
    private MutexShardsKernel() {}

    public static TpcResult run(WorkloadPlan plan) throws InterruptedException {
        long[] counters = new long[TpcFixtures.KEY_COUNT];
        ReentrantLock[] shardLocks = new ReentrantLock[TpcFixtures.SHARD_COUNT];
        for (int s = 0; s < shardLocks.length; s++) shardLocks[s] = new ReentrantLock();

        Thread[] requesters = new Thread[TpcFixtures.REQUESTER_THREADS];
        for (int r = 0; r < requesters.length; r++) {
            int[] chunk = plan.chunkFor(r, requesters.length);
            requesters[r] = new Thread(() -> {
                for (int key : chunk) {
                    ReentrantLock lock = shardLocks[TpcFixtures.shardFor(key)];
                    lock.lock();
                    try {
                        counters[key]++;
                    } finally {
                        lock.unlock();
                    }
                }
            });
        }

        for (Thread t : requesters) t.start();
        for (Thread t : requesters) t.join();

        return new TpcResult(counters.clone(), 0);
    }
}
