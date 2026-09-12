package pl.kzybala.lab.gctail;

/**
 * Deterministic input generation for the three named datasets — the
 * cross-language equivalence contract
 * (../fixtures/gc-tail-latency-fixtures.json). Every variant reads the
 * identical value stream regardless of how it allocates.
 */
public final class GcTailFixtures {

    public static final int N = 1_000_000;

    private GcTailFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    private static long[] stream(long seed, int n) {
        long[] out = new long[n];
        long x = seed;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            out[i] = Long.remainderUnsigned(x, 1_000_000);
        }
        return out;
    }

    /** objectGraphChurn: value[i] = i (linear); extraSize = 8. */
    public static long[] objectGraphChurn(int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = i;
        return out;
    }

    /** messagePipeline: xorshift64 stream, seed=60; extraSize = 16. */
    public static long[] messagePipeline(int n) {
        return stream(60L, n);
    }

    /** retainedCache: xorshift64 stream, seed=61; extraSize = 4. */
    public static long[] retainedCache(int n) {
        return stream(61L, n);
    }

    public static long[] valuesFor(String dataset, int n) {
        return switch (dataset) {
            case "objectGraphChurn" -> objectGraphChurn(n);
            case "messagePipeline" -> messagePipeline(n);
            case "retainedCache" -> retainedCache(n);
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        };
    }

    public static int extraSizeFor(String dataset) {
        return switch (dataset) {
            case "objectGraphChurn" -> 8;
            case "messagePipeline" -> 16;
            case "retainedCache" -> 4;
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        };
    }

    public static long expectedTotal(long[] values) {
        long sum = 0;
        for (long v : values) sum += v;
        return sum;
    }
}
