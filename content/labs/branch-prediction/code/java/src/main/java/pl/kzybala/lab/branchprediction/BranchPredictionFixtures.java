package pl.kzybala.lab.branchprediction;

import java.util.Arrays;

/**
 * Deterministic dataset generation and the two accumulation techniques
 * (branchy, branchless) for the "Branch prediction and data distribution"
 * lab — the cross-language equivalence contract
 * (../fixtures/branch-prediction-fixtures.json). One xorshift64 stream
 * drives three datasets (byte flags, integer thresholds, mixed hot/cold
 * records) and four variants that vary only the arrangement of that data
 * or the technique used to consume it, never the underlying multiset —
 * which is what makes the sorted/branchless-vs-random5050 equality a
 * correctness oracle rather than a coincidence.
 */
public final class BranchPredictionFixtures {

    public static final int N = 1_000_000;
    public static final long SEED = 42L;

    public static final int BYTE_THRESHOLD = 128;
    public static final int INT_THRESHOLD = 500_000;
    public static final int INT_DOMAIN = 1_000_000;
    public static final int PAYLOAD_DOMAIN = 1024;

    private BranchPredictionFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /** Raw xorshift64 stream, unsigned 64-bit values (as Java {@code long} bit patterns). */
    public static long[] stream(long seed, int n) {
        long[] out = new long[n];
        long x = seed;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            out[i] = x;
        }
        return out;
    }

    /** Wrapping checksum (checksum*31 + value) — fixture oracle over the raw stream. */
    public static long checksum(long[] values) {
        long c = 0;
        for (long v : values) c = c * 31 + v;
        return c;
    }

    public static long checksumInts(int[] values) {
        long c = 0;
        for (int v : values) c = c * 31 + v;
        return c;
    }

    // --- byteFlags / intThresholds: a single value array, generated so the
    // predicate (value >= threshold) is true with the given probability ---

    public static int[] biasedValues(int biasPercent, int threshold, int domain, long seed, int n) {
        long[] s = stream(seed, n);
        int[] vals = new int[n];
        int span = domain - threshold;
        for (int i = 0; i < n; i++) {
            boolean above = Long.remainderUnsigned(s[i], 100) < biasPercent;
            vals[i] = above
                ? threshold + (int) Long.remainderUnsigned(s[i], span)
                : (int) Long.remainderUnsigned(s[i], threshold);
        }
        return vals;
    }

    public static int[] sortedAscending(int[] values) {
        int[] copy = values.clone();
        Arrays.sort(copy);
        return copy;
    }

    // --- mixedHotCold: kind (0/1) and payload derived from the same stream value ---

    public static final class Records {
        public final int[] kinds;
        public final int[] payloads;

        Records(int[] kinds, int[] payloads) {
            this.kinds = kinds;
            this.payloads = payloads;
        }
    }

    public static Records biasedRecords(int biasPercent, long seed, int n) {
        long[] s = stream(seed, n);
        int[] kinds = new int[n];
        int[] payloads = new int[n];
        for (int i = 0; i < n; i++) {
            kinds[i] = Long.remainderUnsigned(s[i], 100) < biasPercent ? 1 : 0;
            payloads[i] = (int) Long.remainderUnsigned(s[i], PAYLOAD_DOMAIN);
        }
        return new Records(kinds, payloads);
    }

    /** Stable sort of (kind, payload) pairs by kind ascending — cold block then hot block. */
    public static Records sortedByKind(Records r) {
        int n = r.kinds.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Integer.compare(r.kinds[a], r.kinds[b])); // stable (TimSort)
        int[] kinds = new int[n];
        int[] payloads = new int[n];
        for (int i = 0; i < n; i++) {
            kinds[i] = r.kinds[order[i]];
            payloads[i] = r.payloads[order[i]];
        }
        return new Records(kinds, payloads);
    }

    // --- accumulation techniques ---

    /** Branchy: an explicit conditional guards whether value contributes. */
    public static long filteredSumBranchy(int[] values, int threshold) {
        long sum = 0;
        for (int v : values) {
            if (v >= threshold) sum += v;
        }
        return sum;
    }

    /**
     * Branchless: pure arithmetic mask, no conditional or ternary anywhere
     * in the hot path. {@code diff >>> 31} is 1 when {@code v < threshold}
     * (diff negative) and 0 when {@code v >= threshold}; XOR with 1 flips
     * it into a 0/1 "keep" multiplier — never a cmov-or-branch ambiguity.
     */
    public static long filteredSumBranchless(int[] values, int threshold) {
        long sum = 0;
        for (int v : values) {
            int diff = v - threshold;
            int keep = (diff >>> 31) ^ 1;
            sum += (long) v * keep;
        }
        return sum;
    }

    public static long filteredSumRecordsBranchy(Records r) {
        long sum = 0;
        for (int i = 0; i < r.kinds.length; i++) {
            if (r.kinds[i] == 1) sum += r.payloads[i];
        }
        return sum;
    }

    /** Branchless record variant: kind is already a 0/1 multiplier. */
    public static long filteredSumRecordsBranchless(Records r) {
        long sum = 0;
        for (int i = 0; i < r.kinds.length; i++) {
            sum += (long) r.payloads[i] * r.kinds[i];
        }
        return sum;
    }
}
