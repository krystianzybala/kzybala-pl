package pl.kzybala.lab.cascontention;

/**
 * Companion code for the "CAS contention and backoff" Performance Lab
 * (kzybala.pl/lab/cas-contention/). No CAS, no atomics — correct only
 * because exactly one thread ever calls {@link #increment()}. See java.md
 * "The single-writer alternative".
 */
public final class SingleWriterCounter {
    private long value;

    public long increment() {
        return ++value;
    }

    public long get() {
        return value;
    }
}
