package pl.kzybala.lab.aosvssoa;

/**
 * Variant 4: hybrid hot/cold split. The two hot fields are packed
 * together, interleaved, in one dense array ({@code hotPairs[2*i]=hotA},
 * {@code hotPairs[2*i+1]=hotB}) — a mini-AoS of just the hot fields —
 * while every cold field lives in a separate region entirely. This is
 * neither full AoS (cold data is not interleaved with hot) nor full SoA
 * (hotA and hotB are not in separate arrays): when the operation always
 * needs both hot fields together, one interleaved stream can beat two
 * separate streams, which is exactly the nuance behind this lab's
 * "assuming SoA always wins" trap.
 */
public final class HybridLayout {

    private final long[] hotPairs; // [2*i]=hotA[i], [2*i+1]=hotB[i]
    private final long[] cold; // flat, row-major: record*coldWords + word
    private final int coldWords;

    private HybridLayout(long[] hotPairs, long[] cold, int coldWords) {
        this.hotPairs = hotPairs;
        this.cold = cold;
        this.coldWords = coldWords;
    }

    public static HybridLayout of(AosVsSoaFixtures.Generated data, int coldWords) {
        int n = data.hotA.length;
        long[] hotPairs = new long[n * 2];
        long[] cold = new long[n * coldWords];
        for (int i = 0; i < n; i++) {
            hotPairs[2 * i] = data.hotA[i];
            hotPairs[2 * i + 1] = data.hotB[i];
            System.arraycopy(data.cold[i], 0, cold, i * coldWords, coldWords);
        }
        return new HybridLayout(hotPairs, cold, coldWords);
    }

    public long totalBytes() {
        return (long) (hotPairs.length + cold.length) * 8L;
    }

    /** One operation = one full pass over the single interleaved hot stream. */
    public long sumHot() {
        long sum = 0;
        for (int i = 0; i < hotPairs.length; i += 2) sum += hotPairs[i] + hotPairs[i + 1];
        return sum;
    }

    public long coldChecksum() {
        long c = 0;
        int n = hotPairs.length / 2;
        for (int i = 0; i < n; i++) {
            int base = i * coldWords;
            for (int w = 0; w < coldWords; w++) c = c * 31 + cold[base + w];
        }
        return c;
    }
}
