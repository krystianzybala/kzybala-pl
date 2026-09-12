package pl.kzybala.lab.simd;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The five variants for {@code thresholdFilter}: count elements strictly
 * greater than a fixed threshold. {@code explicitSimd} builds a
 * {@link VectorMask} per vector via {@code compare}, ANDs it with the
 * in-range mask for the tail, and sums {@code trueCount()} — this lab's
 * Java-track "masks" focus applied to a real predicate, not just a load
 * boundary.
 */
public final class ThresholdFilterKernel {

    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

    private ThresholdFilterKernel() {}

    public static long scalarBaseline(int[] value, int offset, int length, int threshold) {
        long count = 0;
        for (int i = offset; i < offset + length; i++) {
            if (value[i] > threshold) count++;
        }
        return count;
    }

    /** Branchless counting idiom — the classic auto-vectorizable shape for a predicate count. */
    public static long autoVectorizedCandidate(int[] value, int offset, int length, int threshold) {
        long count = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            count += (value[i] > threshold) ? 1 : 0;
        }
        return count;
    }

    public static long explicitSimd(int[] value, int offset, int length, int threshold) {
        long count = 0;
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            IntVector v = IntVector.fromArray(SPECIES, value, offset + i);
            VectorMask<Integer> above = v.compare(VectorOperators.GT, threshold);
            count += above.trueCount();
        }
        if (i < length) {
            VectorMask<Integer> inRange = SPECIES.indexInRange(i, length);
            IntVector v = IntVector.fromArray(SPECIES, value, offset + i, inRange);
            VectorMask<Integer> above = v.compare(VectorOperators.GT, threshold).and(inRange);
            count += above.trueCount();
        }
        return count;
    }
}
