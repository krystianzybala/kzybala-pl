package pl.kzybala.lab.deoptimization;

/**
 * Deterministic input generation for the three named datasets — the
 * cross-language equivalence contract
 * (../fixtures/deoptimization-uncommon-traps-fixtures.json). Every
 * variant applies a deterministic sequence of speculative-assumption
 * violations at fixed indices; nothing here is randomized by time or
 * probability.
 */
public final class DeoptFixtures {

    public static final int N = 1_000_000;
    public static final int SHIFT_POINT = 500_000;
    public static final int RARE_EXCEPTION_INDEX = 700_000;
    public static final int NULL_STRIDE = 1000;
    public static final long FALLBACK_EXCEPTION = -1L;
    public static final long FALLBACK_NULL = 0L;

    private DeoptFixtures() {}

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

    /** strategyDispatch: input[i] = i (linear, no RNG). */
    public static long[] strategyDispatch(int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = i;
        return out;
    }

    /** parsingMixedRecords: xorshift64 stream, seed=50. */
    public static long[] parsingMixedRecords(int n) {
        return stream(50L, n);
    }

    /** rareValidationFailure: xorshift64 stream, seed=51. */
    public static long[] rareValidationFailure(int n) {
        return stream(51L, n);
    }

    public static long[] inputsFor(String dataset, int n) {
        return switch (dataset) {
            case "strategyDispatch" -> strategyDispatch(n);
            case "parsingMixedRecords" -> parsingMixedRecords(n);
            case "rareValidationFailure" -> rareValidationFailure(n);
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        };
    }
}
