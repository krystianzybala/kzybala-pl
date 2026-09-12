package pl.kzybala.lab.tpc;

/** Deterministic dataset constants, identical to
 * code/fixtures/thread-per-core-sharding-fixtures.json and mirrored
 * byte-for-byte by the Rust crate's own fixtures module. */
public final class TpcFixtures {
    private TpcFixtures() {}

    public static final int KEY_COUNT = 16;
    public static final int SHARD_COUNT = 4;
    public static final int REQUESTS_PER_KEY_UNIFORM = 500;
    public static final int REQUESTER_THREADS = 4;
    public static final int SKEWED_HOT_KEY_REQUESTS = 4_000;
    public static final int SKEWED_COLD_KEY_REQUESTS = 200;

    public static final long MAX_SPIN_ITERATIONS = 200_000_000L;

    /** Expected final count for {@code key} under the uniform dataset:
     * every key gets exactly {@link #REQUESTS_PER_KEY_UNIFORM} requests. */
    public static int expectedUniform(int key) {
        return REQUESTS_PER_KEY_UNIFORM;
    }

    /** Expected final count for {@code key} under the skewed dataset: key
     * 0 (the "hot key") gets {@link #SKEWED_HOT_KEY_REQUESTS}; every
     * other key gets {@link #SKEWED_COLD_KEY_REQUESTS}. */
    public static int expectedSkewed(int key) {
        return key == 0 ? SKEWED_HOT_KEY_REQUESTS : SKEWED_COLD_KEY_REQUESTS;
    }

    public static int shardFor(int key) {
        return key % SHARD_COUNT;
    }
}
