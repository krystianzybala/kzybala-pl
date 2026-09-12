package pl.kzybala.lab.cachelocality;

/**
 * Deterministic traversal-order generation shared by all four variants —
 * the cross-language equivalence contract
 * (../fixtures/cache-locality-working-set-fixtures.json). {@code
 * values[i] = i} for every dataset (no RNG needed for the payload itself,
 * which sidesteps the "different dataset generation between languages"
 * trap entirely for the data); only the TRAVERSAL ORDER is randomized.
 * Every variant must sum to the identical {@code n*(n-1)/2} — order
 * changes cost, never the total.
 */
public final class CacheLocalityFixtures {

    public static final int N = 1_000_000;
    public static final long SEED = 42L;
    public static final int SIDE = 1_000; // SIDE*SIDE = N, for the blocked variant
    public static final int BLOCK_SIZE = 32;

    private CacheLocalityFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    public static long expectedTotal(int n) {
        return (long) n * (n - 1) / 2;
    }

    /**
     * Fisher-Yates: a genuine random permutation of [0, n) (self-swaps
     * allowed, {@code j} inclusive of {@code i}).
     */
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

    /**
     * Sattolo's algorithm: a single n-cycle ({@code j} EXCLUDES {@code i},
     * unlike Fisher-Yates) — identical algorithm to
     * content/labs/cache-hierarchy's {@code ChaseTables.randomCycle}, so
     * a reader already familiar with that lab recognizes it immediately.
     * Guarantees a dependent pointer chase visits every element exactly
     * once before returning to the start.
     */
    public static long[] sattoloNext(long seed, int n) {
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        long state = Math.max(seed, 1);
        for (int i = n - 1; i > 0; i--) {
            state = xorshift64(state);
            int j = (int) Long.remainderUnsigned(state, i);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        long[] next = new long[n];
        for (int i = 0; i < n; i++) next[perm[i]] = perm[(i + 1) % n];
        return next;
    }

    public static long checksum(int[] values) {
        long c = 0;
        for (int v : values) c = c * 31 + v;
        return c;
    }

    /** Follows {@code next} from index 0 for {@code next.length} steps. */
    public static long traversalChecksum(long[] next) {
        long checksum = 0;
        long idx = 0;
        for (int step = 0; step < next.length; step++) {
            idx = next[(int) idx];
            checksum = checksum * 31 + idx;
        }
        return checksum;
    }

    // --- the four operations: each sums values[i]=i in a different order ---

    /** One operation = one full pass, index 0..n-1 in natural order. */
    public static long sumSequential(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += i;
        return sum;
    }

    /** One operation = one full pass following a random permutation. */
    public static long sumPermuted(int[] perm) {
        long sum = 0;
        for (int idx : perm) sum += idx;
        return sum;
    }

    /** One operation = one full dependent pointer-chase pass. */
    public static long sumPointerChase(long[] next) {
        long sum = 0;
        long idx = 0;
        for (int step = 0; step < next.length; step++) {
            idx = next[(int) idx];
            sum += idx;
        }
        return sum;
    }

    /**
     * One operation = one full tiled column-major pass over a
     * {@code side x side} row-major matrix, decomposed into
     * {@code blockSize x blockSize} tiles: block-columns outer, block-rows
     * next, then a small row-major scan inside each tile. Recovers most
     * of the locality a naive full column-major pass would lose.
     */
    public static long sumBlocked(int side, int blockSize) {
        long sum = 0;
        for (int bc = 0; bc < side; bc += blockSize) {
            int cEnd = Math.min(bc + blockSize, side);
            for (int br = 0; br < side; br += blockSize) {
                int rEnd = Math.min(br + blockSize, side);
                for (int c = bc; c < cEnd; c++) {
                    for (int r = br; r < rEnd; r++) {
                        sum += (long) r * side + c;
                    }
                }
            }
        }
        return sum;
    }
}
