package pl.kzybala.lab.boundschecks;

/**
 * Deterministic backing-array generation and the shared access-order
 * permutation for the "Bounds checks and loop shape" lab — the cross-
 * language equivalence contract
 * (../fixtures/bounds-checks-loop-shape-fixtures.json). Every backing
 * array holds {@code backing[i] = i}; the permutation here is the
 * IDENTICAL Fisher-Yates construction already established in
 * content/labs/cache-locality-working-set (same seed, same algorithm,
 * same numbers), reused rather than reinvented.
 */
public final class BoundsChecksFixtures {

    public static final int N = 1_000_000;
    public static final long SEED = 42L;
    public static final int STRIDE = 4;
    public static final int SLICE_START = 500_000;

    private BoundsChecksFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    public static int[] buildBacking(int length) {
        int[] backing = new int[length];
        for (int i = 0; i < length; i++) backing[i] = i;
        return backing;
    }

    /** Fisher-Yates: identical to content/labs/cache-locality-working-set's randomPermutation. */
    public static int[] randomPermutation(long seed, int n) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        long state = Math.max(seed, 1);
        for (int i = n - 1; i > 0; i--) {
            state = xorshift64(state);
            int j = (int) Long.remainderUnsigned(state, i + 1);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        return perm;
    }

    public static long expectedTotal(String dataset) {
        return switch (dataset) {
            case "primitiveArrays" -> (long) N * (N - 1) / 2;
            case "slicesSubranges" -> {
                long a = SLICE_START;
                long b = SLICE_START + N - 1;
                yield (a + b) * N / 2;
            }
            case "stridedAccess" -> (long) STRIDE * N * (N - 1) / 2;
            default -> throw new IllegalArgumentException("unknown dataset: " + dataset);
        };
    }
}
