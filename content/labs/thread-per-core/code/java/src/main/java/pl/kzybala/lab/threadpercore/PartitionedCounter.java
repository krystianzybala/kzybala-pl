package pl.kzybala.lab.threadpercore;

/**
 * Companion code for the "Thread-per-Core Architecture" Performance Lab
 * (kzybala.pl/lab/thread-per-core/). See java.md for the full explanation.
 *
 * One instance per core/partition. No synchronization at all — correct
 * only because exactly one thread (the one that owns this partition) ever
 * calls {@link #increment()}. Contrast with {@link SharedCounterPool},
 * which protects the same kind of state with a lock because it is shared
 * across every worker thread.
 */
public final class PartitionedCounter {
    private long value;

    public long increment() {
        return ++value;
    }

    public long get() {
        return value;
    }
}
