package pl.kzybala.lab.locks;

/** Deterministic dataset constants, identical to
 * code/fixtures/locks-spin-parking-fixtures.json and mirrored
 * byte-for-byte by the Rust crate's own fixtures module. */
public final class LocksFixtures {
    private LocksFixtures() {}

    public static final int WORKER_COUNT = 4;
    public static final int OPS_PER_WORKER = 2_000;
    public static final int LONG_CS_BUSY_ITERATIONS = 200;
    public static final int SPIN_LIMIT = 100;

    public static final long MAX_SPIN_ITERATIONS = 200_000_000L;

    /** Deterministic, side-effect-free busy work used inside the "long
     * critical section" variant — its cost does not depend on contention,
     * only on {@code iterations}, so it is a controlled knob rather than
     * an accidental one. */
    public static long busyWork(int iterations) {
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            acc += (i * 2654435761L) ^ i;
        }
        return acc;
    }
}
