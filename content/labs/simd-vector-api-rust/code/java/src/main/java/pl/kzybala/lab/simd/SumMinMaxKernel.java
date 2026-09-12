package pl.kzybala.lab.simd;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The five variants for {@code sumMinMax}: sum, min and max of an
 * {@code int[]} slice. {@code explicitSimd} handles its tail with a
 * {@link VectorMask} rather than a separate scalar loop — this lab's
 * Java-track focus on masks made concrete. {@code misalignedInput} and
 * {@code smallTailHeavyInput} run the IDENTICAL {@code explicitSimd}
 * kernel over a different input SHAPE (offset by one element; truncated
 * to 17 elements) — the operation is unchanged, only the data is.
 */
public final class SumMinMaxKernel {

    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

    private SumMinMaxKernel() {}

    public record Result(long sum, int min, int max) {}

    /** Plain scalar loop — whether C2 auto-vectorizes this is JIT/host-dependent and never assumed (benchmark.md). */
    public static Result scalarBaseline(int[] value, int offset, int length) {
        long sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = offset; i < offset + length; i++) {
            int v = value[i];
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return new Result(sum, min, max);
    }

    /** The canonical single-accumulator, no-branch-inside-the-hot-path counted-loop shape superword auto-vectorization targets. */
    public static Result autoVectorizedCandidate(int[] value, int offset, int length) {
        long sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int v = value[i];
            sum += v;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return new Result(sum, min, max);
    }

    /**
     * A real bug found while building this lab: accumulating SUM inside
     * an {@code IntVector} register across many iterations silently
     * overflows int32 range (each of the 4 lanes accumulates roughly
     * 250,000 values averaging ~500,000 for this dataset — an int32
     * accumulator overflows by roughly 60x). A scalar {@code long sum}
     * never has this problem because it widens automatically; a vector
     * accumulator does not. The fix flushes the int accumulator into a
     * scalar {@code long} total every {@code FLUSH_INTERVAL} vector
     * iterations, then resets it to zero (java.md).
     *
     * <p>A SECOND, host-dependent instance of the same bug class was
     * found when this lab first ran on the native-Linux publication
     * host: a fixed {@code FLUSH_INTERVAL = 1000} was tuned only against
     * this development machine's {@code SPECIES_PREFERRED} lane count
     * (4, on this Arm/NEON host) and was safe there — but
     * {@code reduceLanesToLong} reduces all lanes together in native
     * {@code int} arithmetic before widening the single reduced value to
     * {@code long} (it does not widen each lane independently first), so
     * the real overflow bound is the SUM ACROSS ALL LANES per flush, not
     * the per-lane sum. On the AVX2 publication host
     * {@code SPECIES_PREFERRED} has 8 lanes, doubling that cross-lane
     * total and silently wrapping {@code int32} — the correctness gate
     * caught a wrong result (a large negative sum) the first time this
     * ran on real x86_64 hardware. The fix below scales
     * {@code FLUSH_INTERVAL} inversely with {@link #SPECIES}'s lane
     * count so the cross-lane total per flush stays bounded regardless
     * of host vector width — a fixed magic number tied to one
     * developer's CPU is exactly the kind of "assumed vector width"
     * portability trap this lab teaches (theory.md, exercises.md).
     */
    private static final int MAX_VALUE_BOUND = 1_000_000; // exclusive upper bound of this dataset's values
    private static final long SAFE_CROSS_LANE_SUM_BOUND = 200_000_000L; // well under Integer.MAX_VALUE even if reduced in native int32 first
    private static final int FLUSH_INTERVAL =
            Math.max(1, (int) (SAFE_CROSS_LANE_SUM_BOUND / ((long) SPECIES.length() * MAX_VALUE_BOUND)));

    /** Explicit jdk.incubator.vector: full-width lanes plus one masked tail — no separate scalar tail loop. */
    public static Result explicitSimd(int[] value, int offset, int length) {
        IntVector sumAcc = IntVector.zero(SPECIES);
        IntVector minAcc = IntVector.broadcast(SPECIES, Integer.MAX_VALUE);
        IntVector maxAcc = IntVector.broadcast(SPECIES, Integer.MIN_VALUE);
        long sum = 0;
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        int sinceFlush = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            IntVector v = IntVector.fromArray(SPECIES, value, offset + i);
            sumAcc = sumAcc.add(v);
            minAcc = minAcc.min(v);
            maxAcc = maxAcc.max(v);
            if (++sinceFlush == FLUSH_INTERVAL) {
                sum += sumAcc.reduceLanesToLong(VectorOperators.ADD);
                sumAcc = IntVector.zero(SPECIES);
                sinceFlush = 0;
            }
        }
        if (i < length) {
            VectorMask<Integer> mask = SPECIES.indexInRange(i, length);
            IntVector v = IntVector.fromArray(SPECIES, value, offset + i, mask);
            sumAcc = sumAcc.add(v, mask);
            minAcc = minAcc.min(v.blend(Integer.MAX_VALUE, mask.not()));
            maxAcc = maxAcc.max(v.blend(Integer.MIN_VALUE, mask.not()));
        }
        sum += sumAcc.reduceLanesToLong(VectorOperators.ADD);
        int min = minAcc.reduceLanes(VectorOperators.MIN);
        int max = maxAcc.reduceLanes(VectorOperators.MAX);
        return new Result(sum, min, max);
    }
}
