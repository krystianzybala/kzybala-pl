package pl.kzybala.lab.threadpercore;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Companion code for the "Thread-per-Core Architecture" Performance Lab
 * (kzybala.pl/lab/thread-per-core/). See java.md for the full explanation.
 *
 * The shared-worker-pool baseline: every partition's counter lives in one
 * array, guarded by one {@link ReentrantLock}. Any worker thread may
 * increment any partition, but only one increment — on any partition —
 * can proceed at a time, because they all serialize on the same lock.
 * Contrast with {@link PartitionedCounter}, where each partition has its
 * own instance touched by exactly one owning thread and needs no lock.
 */
public final class SharedCounterPool {
    private final long[] counters;
    private final ReentrantLock lock = new ReentrantLock();

    public SharedCounterPool(int partitions) {
        this.counters = new long[partitions];
    }

    public long increment(int partition) {
        lock.lock();
        try {
            return ++counters[partition];
        } finally {
            lock.unlock();
        }
    }

    public long get(int partition) {
        lock.lock();
        try {
            return counters[partition];
        } finally {
            lock.unlock();
        }
    }
}
