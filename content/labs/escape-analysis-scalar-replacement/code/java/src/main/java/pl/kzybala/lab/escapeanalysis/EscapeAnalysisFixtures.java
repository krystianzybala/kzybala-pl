package pl.kzybala.lab.escapeanalysis;

/**
 * Deterministic input generation for the three named datasets — the
 * cross-language equivalence contract
 * (../fixtures/escape-analysis-scalar-replacement-fixtures.json).
 */
public final class EscapeAnalysisFixtures {

    public static final int N = 1_000_000;

    private EscapeAnalysisFixtures() {}

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

    public record Pair(long[] x, long[] y) {}

    /** coordinate: x[i] = i (linear); y = xorshift64 stream seed=42. */
    public static Pair coordinate(int n) {
        long[] x = new long[n];
        for (int i = 0; i < n; i++) x[i] = i;
        return new Pair(x, stream(42L, n));
    }

    /** resultWrapper: x = xorshift64 stream seed=43; y = seed=44. */
    public static Pair resultWrapper(int n) {
        return new Pair(stream(43L, n), stream(44L, n));
    }

    /** parserState: x = xorshift64 stream seed=45; y = seed=46. */
    public static Pair parserState(int n) {
        return new Pair(stream(45L, n), stream(46L, n));
    }

    public static Pair pairFor(String dataset, int n) {
        return switch (dataset) {
            case "coordinate" -> coordinate(n);
            case "resultWrapper" -> resultWrapper(n);
            case "parserState" -> parserState(n);
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        };
    }

    public static long expectedTotal(long[] x, long[] y) {
        long sum = 0;
        for (int i = 0; i < x.length; i++) sum += x[i] + y[i];
        return sum;
    }
}
