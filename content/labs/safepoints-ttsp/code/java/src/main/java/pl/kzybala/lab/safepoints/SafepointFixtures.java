package pl.kzybala.lab.safepoints;

/**
 * Fixed, documented constants for every dataset/variant — the cross-
 * language equivalence contract
 * (../fixtures/safepoints-ttsp-fixtures.json). Every dataset processes
 * the identical logical range {@code [0, TOTAL_ITERATIONS)} via {@code
 * sum(i)}; partitioning it across workers never changes the total,
 * because integer summation is partition-invariant.
 */
public final class SafepointFixtures {

    public static final long TOTAL_ITERATIONS = 400_000_000L;
    public static final long CHUNK_SIZE = 50_000_000L;
    public static final int NUM_CHUNKS = 8;
    public static final int FLEET_SIZE = 16;
    public static final long PER_FLEET_WORKER_ITERATIONS = TOTAL_ITERATIONS / FLEET_SIZE;
    public static final int IDLE_THREAD_COUNT = 300;
    public static final int NATIVE_SLEEP_MICROS = 50_000;
    public static final long EXPECTED_TOTAL = 79_999_999_800_000_000L;

    private SafepointFixtures() {}

    /** sum(i) for i in [start, start+count), 64-bit wrapping. */
    public static long sumRange(long start, long count) {
        long sum = 0;
        long end = start + count;
        for (long i = start; i < end; i++) sum += i;
        return sum;
    }
}
