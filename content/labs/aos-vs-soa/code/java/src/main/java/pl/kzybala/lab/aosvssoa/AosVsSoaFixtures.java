package pl.kzybala.lab.aosvssoa;

/**
 * Deterministic record generation shared by all four layouts — the
 * cross-language equivalence contract
 * (../fixtures/aos-vs-soa-fixtures.json). Every record has two "hot"
 * fields (hotA, hotB) and a dataset-specific number of "cold" fields,
 * generated once as plain arrays; each layout class is responsible for
 * storing those exact values in its own representation and must
 * reproduce the identical {@code hotSum} — layout changes storage, never
 * the data.
 */
public final class AosVsSoaFixtures {

    public static final int N = 1_000_000;
    public static final long SEED = 42L;
    public static final int HOT_DOMAIN = 1_000_000;

    private AosVsSoaFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    public static final class Generated {
        public final long[] hotA;
        public final long[] hotB;
        public final long[][] cold; // [record][coldWord]

        Generated(long[] hotA, long[] hotB, long[][] cold) {
            this.hotA = hotA;
            this.hotB = hotB;
            this.cold = cold;
        }
    }

    /** Generation order per record: hotA, hotB, then coldWords cold values. */
    public static Generated generate(int coldWords, long seed, int n) {
        long[] hotA = new long[n];
        long[] hotB = new long[n];
        long[][] cold = new long[n][coldWords];
        long x = seed;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            hotA[i] = Long.remainderUnsigned(x, HOT_DOMAIN);
            x = xorshift64(x);
            hotB[i] = Long.remainderUnsigned(x, HOT_DOMAIN);
            for (int w = 0; w < coldWords; w++) {
                x = xorshift64(x);
                cold[i][w] = Long.remainderUnsigned(x, HOT_DOMAIN);
            }
        }
        return new Generated(hotA, hotB, cold);
    }

    public static long checksum(long[] values) {
        long c = 0;
        for (long v : values) c = c * 31 + v;
        return c;
    }

    /** Row-major (record then cold word) checksum — proves no data loss across layouts. */
    public static long checksumCold(long[][] cold) {
        long c = 0;
        for (long[] row : cold) {
            for (long v : row) c = c * 31 + v;
        }
        return c;
    }

    /** The layout-invariance oracle: wrapping sum of (hotA[i] + hotB[i]) over all records. */
    public static long expectedHotSum(long[] hotA, long[] hotB) {
        long sum = 0;
        for (int i = 0; i < hotA.length; i++) sum += hotA[i] + hotB[i];
        return sum;
    }
}
