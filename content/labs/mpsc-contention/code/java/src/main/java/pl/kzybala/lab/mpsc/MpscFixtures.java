package pl.kzybala.lab.mpsc;

/** Deterministic dataset constants, identical to
 * code/fixtures/mpsc-contention-fixtures.json and mirrored byte-for-byte
 * by the Rust crate's own fixtures module. */
public final class MpscFixtures {
    private MpscFixtures() {}

    public static final int[] PRODUCER_COUNTS = {2, 4};
    public static final int ITEMS_PER_PRODUCER = 2_000;
    /** Bounded-queue capacity for the shared/batched variants — a power
     * of two so slot index can be derived with a mask. */
    public static final int CAPACITY = 1_024;
    public static final int BATCH_SIZE = 32;

    /** Bounded-spin safety net, matching this repository's other labs
     * (see docs/incidents/2026-07-17-spsc-jmh-hang.md): every retry loop
     * here is bounded, no exceptions. */
    public static final long MAX_SPIN_ITERATIONS = 200_000_000L;

    public static long encode(int producerId, int localSeq) {
        return ((long) producerId << 32) | (localSeq & 0xFFFFFFFFL);
    }

    public static int decodeProducer(long item) {
        return (int) (item >>> 32);
    }

    public static int decodeSeq(long item) {
        return (int) item;
    }
}
