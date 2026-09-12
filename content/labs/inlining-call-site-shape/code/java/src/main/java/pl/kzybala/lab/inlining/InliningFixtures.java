package pl.kzybala.lab.inlining;

/**
 * Deterministic input generation for the three named datasets — the
 * cross-language equivalence contract
 * (../fixtures/inlining-call-site-shape-fixtures.json). The dispatch
 * loops that consume these inputs live in {@link InliningOperations}.
 */
public final class InliningFixtures {

    public static final int N = 1_000_000;

    private InliningFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /** pricingFunctions: input[i] = i — linear, no RNG. */
    public static long[] pricingFunctions(int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = i;
        return out;
    }

    /** codecStrategies: xorshift64 stream, seed=42, unsigned-mod 1_000_000. */
    public static long[] codecStrategies(int n) {
        return stream(42L, n);
    }

    /** validationRules: xorshift64 stream, seed=43, unsigned-mod 1_000_000. */
    public static long[] validationRules(int n) {
        return stream(43L, n);
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

    public static long[] inputsFor(String dataset, int n) {
        return switch (dataset) {
            case "pricingFunctions" -> pricingFunctions(n);
            case "codecStrategies" -> codecStrategies(n);
            case "validationRules" -> validationRules(n);
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        };
    }
}
