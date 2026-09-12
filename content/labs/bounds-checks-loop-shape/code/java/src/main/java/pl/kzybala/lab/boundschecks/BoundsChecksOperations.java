package pl.kzybala.lab.boundschecks;

import org.openjdk.jmh.annotations.CompilerControl;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.stream.IntStream;

/**
 * The five variants — canonical indexed loop, opaque limit provider,
 * irregular index array, safe iterator, isolated unchecked access — over
 * the three datasets. Every method sums exactly the values this lab's
 * fixture declares (../fixtures/bounds-checks-loop-shape-fixtures.json);
 * setup (backing/slice/permutation construction) always happens before
 * these methods are called, never inside them.
 *
 * <p>{@link #opaqueLength} is annotated {@code DONT_INLINE} specifically
 * so C2 cannot see through the call and prove the returned bound equals
 * {@code backing.length} — the textbook way to deliberately defeat range-
 * check elimination (theory.md), not an accident of measurement.
 */
public final class BoundsChecksOperations {

    private BoundsChecksOperations() {}

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int opaqueLength(int[] backing) {
        return backing.length;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int opaqueSliceStart(int start) {
        return start;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static int opaqueSliceEnd(int end) {
        return end;
    }

    // --- primitiveArrays: backing.length == N -----------------------------

    public static long primitiveCanonical(int[] backing) {
        long sum = 0;
        for (int i = 0; i < backing.length; i++) sum += backing[i];
        return sum;
    }

    public static long primitiveOpaqueLimit(int[] backing) {
        int n = opaqueLength(backing);
        long sum = 0;
        for (int i = 0; i < n; i++) sum += backing[i];
        return sum;
    }

    public static long primitiveIrregularIndex(int[] backing, int[] perm) {
        long sum = 0;
        for (int idx : perm) sum += backing[idx];
        return sum;
    }

    public static long primitiveSafeIterator(int[] backing) {
        long sum = 0;
        for (int v : backing) sum += v;
        return sum;
    }

    public static long primitiveUnchecked(MemorySegment segment, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += segment.get(ValueLayout.JAVA_INT, (long) i * Integer.BYTES);
        }
        return sum;
    }

    // --- slicesSubranges: backing.length == 2N, window [sliceStart, sliceStart+N) ---
    // Java has no zero-copy borrowed-slice view for a primitive array —
    // canonical/opaque/irregular/unchecked index the ORIGINAL backing
    // array with explicit bounds; safeIterator receives a pre-copied
    // sub-array (copy made in @Setup, never in the timed method) so its
    // timed shape (plain enhanced-for) matches Rust's genuine &[i32]
    // iteration — see java.md's equivalence-contract note.

    public static long sliceCanonical(int[] backing, int start, int end) {
        long sum = 0;
        for (int i = start; i < end; i++) sum += backing[i];
        return sum;
    }

    public static long sliceOpaqueLimit(int[] backing, int start, int end) {
        int s = opaqueSliceStart(start);
        int e = opaqueSliceEnd(end);
        long sum = 0;
        for (int i = s; i < e; i++) sum += backing[i];
        return sum;
    }

    public static long sliceIrregularIndex(int[] backing, int[] perm, int sliceStart) {
        long sum = 0;
        for (int idx : perm) sum += backing[sliceStart + idx];
        return sum;
    }

    /** {@code copiedSlice} must be built once in setup via Arrays.copyOfRange. */
    public static long sliceSafeIterator(int[] copiedSlice) {
        long sum = 0;
        for (int v : copiedSlice) sum += v;
        return sum;
    }

    public static long sliceUnchecked(MemorySegment segment, int start, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += segment.get(ValueLayout.JAVA_INT, (long) (start + i) * Integer.BYTES);
        }
        return sum;
    }

    // --- stridedAccess: backing.length == N*stride, visit backing[i*stride] ---

    public static long stridedCanonical(int[] backing, int n, int stride) {
        long sum = 0;
        for (int i = 0; i < n; i++) sum += backing[i * stride];
        return sum;
    }

    public static long stridedOpaqueLimit(int[] backing, int n, int stride) {
        int limit = opaqueLength(backing) / stride;
        long sum = 0;
        for (int i = 0; i < limit; i++) sum += backing[i * stride];
        return sum;
    }

    public static long stridedIrregularIndex(int[] backing, int[] perm, int stride) {
        long sum = 0;
        for (int idx : perm) sum += backing[idx * stride];
        return sum;
    }

    public static long stridedSafeIterator(int[] backing, int n, int stride) {
        return IntStream.range(0, n).mapToLong(i -> backing[i * stride]).sum();
    }

    public static long stridedUnchecked(MemorySegment segment, int n, int stride) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += segment.get(ValueLayout.JAVA_INT, (long) (i * stride) * Integer.BYTES);
        }
        return sum;
    }
}
