package pl.kzybala.lab.cascontention;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Companion code for the "CAS contention and backoff" Performance Lab
 * (kzybala.pl/lab/cas-contention/). See java.md for the full explanation.
 */
public final class CasCounter {
    private final AtomicLong value = new AtomicLong();

    /** {@code updateAndGet} already implements the retry loop internally. */
    public long incrementViaBuiltin() {
        return value.updateAndGet(v -> v + 1);
    }

    /** The manual form of exactly what {@link #incrementViaBuiltin()} does. */
    public long incrementManually() {
        long old, updated;
        do {
            old = value.get();
            updated = old + 1;
        } while (!value.compareAndSet(old, updated));
        return updated;
    }

    public long get() {
        return value.get();
    }
}
