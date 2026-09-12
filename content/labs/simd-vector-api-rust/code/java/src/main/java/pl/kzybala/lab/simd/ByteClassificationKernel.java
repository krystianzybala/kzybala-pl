package pl.kzybala.lab.simd;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The five variants for {@code byteClassification}: count bytes in the
 * printable-ASCII range {@code [0x20, 0x7E]}. Since that range never
 * crosses the sign boundary (0..127 reads identically whether the byte
 * is interpreted signed or unsigned), plain signed {@code byte}
 * comparisons are correct here without any unsigned-conversion dance —
 * stated explicitly because it is a real, easy-to-get-wrong detail for
 * a wider byte range (theory.md).
 */
public final class ByteClassificationKernel {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final byte LOW = 0x20;
    private static final byte HIGH = 0x7E;

    private ByteClassificationKernel() {}

    public static long scalarBaseline(byte[] value, int offset, int length) {
        long count = 0;
        for (int i = offset; i < offset + length; i++) {
            byte v = value[i];
            if (v >= LOW && v <= HIGH) count++;
        }
        return count;
    }

    public static long autoVectorizedCandidate(byte[] value, int offset, int length) {
        long count = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            byte v = value[i];
            count += (v >= LOW && v <= HIGH) ? 1 : 0;
        }
        return count;
    }

    public static long explicitSimd(byte[] value, int offset, int length) {
        long count = 0;
        int upperBound = SPECIES.loopBound(length);
        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            ByteVector v = ByteVector.fromArray(SPECIES, value, offset + i);
            VectorMask<Byte> ge = v.compare(VectorOperators.GE, LOW);
            VectorMask<Byte> le = v.compare(VectorOperators.LE, HIGH);
            count += ge.and(le).trueCount();
        }
        if (i < length) {
            VectorMask<Byte> inRange = SPECIES.indexInRange(i, length);
            ByteVector v = ByteVector.fromArray(SPECIES, value, offset + i, inRange);
            VectorMask<Byte> ge = v.compare(VectorOperators.GE, LOW);
            VectorMask<Byte> le = v.compare(VectorOperators.LE, HIGH);
            count += ge.and(le).and(inRange).trueCount();
        }
        return count;
    }
}
