package pl.kzybala.lab.memord;

/** Deterministic dataset constants, identical to
 * code/fixtures/memory-ordering-atomics-fixtures.json and mirrored
 * byte-for-byte by the Rust crate's own fixtures module. */
public final class MemOrdFixtures {
    private MemOrdFixtures() {}

    public static final int MAILBOX_PAYLOAD_A = 123_456_789;
    public static final int MAILBOX_PAYLOAD_B = 987_654_321;
    /** Correctness-suite trial count — deliberately smaller than a
     * dedicated stress/evidence run would use, to keep `mvn test` fast;
     * the mechanism does not depend on the exact count. */
    public static final int MAILBOX_TRIALS = 2_000;

    public static final int SEQFLAG_UPDATE_COUNT = 2_000;
    public static final int SEQFLAG_TRIALS = 50;

    public static int seqflagPayload(int seq) {
        return seq * 7 + 3;
    }

    public static final int COUNTER_THREAD_COUNT = 4;
    public static final int COUNTER_INCREMENTS_PER_THREAD = 50_000;
    public static final long COUNTER_EXPECTED_TOTAL =
            (long) COUNTER_THREAD_COUNT * COUNTER_INCREMENTS_PER_THREAD;

    /** Bounded-spin safety net: every busy-wait reader loop in this lab
     * caps at this many iterations before reporting "timedOut" rather
     * than looping indefinitely — a plain (non-volatile) field read
     * inside a tight scalar loop is a real, documented HotSpot C2 hazard
     * (the read can be hoisted out of the loop), and this repository's
     * own history (docs/incidents/2026-07-17-spsc-jmh-hang.md) is why
     * every retry loop here is bounded, no exceptions. */
    public static final long MAX_SPIN_ITERATIONS = 200_000_000L;
}
