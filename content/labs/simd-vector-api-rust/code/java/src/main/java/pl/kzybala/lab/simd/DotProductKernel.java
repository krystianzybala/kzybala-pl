package pl.kzybala.lab.simd;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The five variants for {@code dotProduct}: {@code sum(a[i] * b[i])}.
 * {@code explicitSimd} uses {@code fma} (fused multiply-add) per lane —
 * a single vector instruction on hosts that support it. Every element
 * of this dataset is a small integer exactly representable as
 * {@code double} (fixtures.json), so this reduction is exact regardless
 * of lane-vs-scalar summation order — no floating-point tolerance is
 * ever needed to compare variants (theory.md).
 */
public final class DotProductKernel {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;

    private DotProductKernel() {}

    public static double scalarBaseline(double[] a, double[] b, int offset, int length) {
        double sum = 0;
        for (int i = offset; i < offset + length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    public static double autoVectorizedCandidate(double[] a, double[] b, int offset, int length) {
        double sum = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            sum = Math.fma(a[i], b[i], sum);
        }
        return sum;
    }

    public static double explicitSimd(double[] a, double[] b, int offset, int length) {
        DoubleVector acc = DoubleVector.zero(SPECIES);
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            DoubleVector va = DoubleVector.fromArray(SPECIES, a, offset + i);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, b, offset + i);
            acc = va.fma(vb, acc);
        }
        if (i < length) {
            VectorMask<Double> mask = SPECIES.indexInRange(i, length);
            DoubleVector va = DoubleVector.fromArray(SPECIES, a, offset + i, mask);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, b, offset + i, mask);
            acc = acc.add(va.mul(vb), mask);
        }
        return acc.reduceLanes(VectorOperators.ADD);
    }
}
