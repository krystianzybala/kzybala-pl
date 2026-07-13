package pl.kzybala.lab.cachehierarchy;

import java.util.Random;

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
        Random rnd = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            // nextInt(i), not nextInt(i + 1) as in Fisher-Yates — excluding
            // self-swaps is what makes Sattolo's algorithm produce a single
            // cycle instead of a random permutation (usually several
            // disjoint cycles).
            int j = rnd.nextInt(i);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        long[] next = new long[size];
        for (int i = 0; i < size; i++) next[perm[i]] = perm[(i + 1) % size];
        return next;
    }
}
