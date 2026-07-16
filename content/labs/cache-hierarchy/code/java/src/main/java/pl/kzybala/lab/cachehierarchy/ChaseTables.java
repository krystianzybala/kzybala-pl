package pl.kzybala.lab.cachehierarchy;

/**
 * Builds pointer-chase tables for the cache-hierarchy benchmark. Each
 * table is a single N-element cycle: starting at index 0 and repeatedly
 * following {@code next[idx]} visits every element exactly once before
 * returning to 0. See java.md for why pointer chasing (a dependent access
 * chain) rather than a plain loop is used to isolate memory latency.
 */
public final class ChaseTables {
    private ChaseTables() {}

    /** A sequential cycle: 0 -> 1 -> 2 -> ... -> size-1 -> 0. Maximal spatial locality. */
    public static long[] sequentialCycle(int size) {
        long[] next = new long[size];
        for (int i = 0; i < size; i++) next[i] = (i + 1) % size;
        return next;
    }

    /**
     * A random single-cycle permutation built with Sattolo's algorithm.
     * Plain Fisher-Yates would risk producing several short sub-cycles,
     * which would let a pointer chase loop through only a small hot
     * subset of the array — Sattolo's algorithm guarantees exactly one
     * cycle covering all {@code size} elements.
     */
    public static long[] randomCycle(int size, long seed) {
        int[] perm = new int[size];
        for (int i = 0; i < size; i++) perm[i] = i;
        // xorshift64 — byte-for-byte the same PRNG and Sattolo walk as the
        // Rust implementation (random_cycle in src/lib.rs), so both
        // languages chase the IDENTICAL table for a given (size, seed).
        // The shared fixture pins traversal checksums for both suites.
        long state = Math.max(seed, 1);
        for (int i = size - 1; i > 0; i--) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            // next_rand(i), not next_rand(i + 1) as in Fisher-Yates —
            // excluding self-swaps is what makes Sattolo's algorithm produce
            // a single cycle instead of a random permutation.
            int j = (int) Long.remainderUnsigned(state, i);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        long[] next = new long[size];
        for (int i = 0; i < size; i++) next[perm[i]] = perm[(i + 1) % size];
        return next;
    }

    /**
     * Traversal checksum: follows the cycle from index 0 for exactly
     * {@code size} steps, mixing every visited index. Identical in Rust
     * ({@code traversal_checksum}) — the semantic-equivalence oracle that
     * proves both languages built the same table.
     */
    public static long traversalChecksum(long[] next) {
        long checksum = 0;
        long idx = 0;
        for (int step = 0; step < next.length; step++) {
            idx = next[(int) idx];
            checksum = checksum * 31 + idx;
        }
        return checksum;
    }
}
