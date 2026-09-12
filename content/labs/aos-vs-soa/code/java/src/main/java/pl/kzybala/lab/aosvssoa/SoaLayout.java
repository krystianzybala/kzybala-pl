package pl.kzybala.lab.aosvssoa;

/**
 * Variant 3: SoA primitive vectors. Every field gets its own dense
 * primitive array — {@code hotA[]} and {@code hotB[]} are each fully
 * contiguous, so a hot-field-only scan streams two dense arrays and never
 * touches a cold byte. This is the layout the lab's hypothesis expects to
 * win a read-mostly scan over selected fields — and the one the "assuming
 * SoA always wins" trap (benchmark.md) exists to correct, because this
 * lab's operation reads hotA AND hotB together, which means SoA pays for
 * two separate streams where {@link HybridLayout} pays for one.
 */
public final class SoaLayout {

    private final long[] hotA;
    private final long[] hotB;
    private final long[] cold; // flat, row-major: record*coldWords + word
    private final int coldWords;

    private SoaLayout(long[] hotA, long[] hotB, long[] cold, int coldWords) {
        this.hotA = hotA;
        this.hotB = hotB;
        this.cold = cold;
        this.coldWords = coldWords;
    }

    public static SoaLayout of(AosVsSoaFixtures.Generated data, int coldWords) {
        int n = data.hotA.length;
        long[] cold = new long[n * coldWords];
        for (int i = 0; i < n; i++) {
            System.arraycopy(data.cold[i], 0, cold, i * coldWords, coldWords);
        }
        return new SoaLayout(data.hotA.clone(), data.hotB.clone(), cold, coldWords);
    }

    public long totalBytes() {
        return (long) (hotA.length + hotB.length + cold.length) * 8L;
    }

    /** One operation = one full pass touching only the two hot arrays. */
    public long sumHot() {
        long sum = 0;
        for (int i = 0; i < hotA.length; i++) sum += hotA[i] + hotB[i];
        return sum;
    }

    public long coldChecksum() {
        long c = 0;
        int n = hotA.length;
        for (int i = 0; i < n; i++) {
            int base = i * coldWords;
            for (int w = 0; w < coldWords; w++) c = c * 31 + cold[base + w];
        }
        return c;
    }
}
