package pl.kzybala.lab.escapeanalysis;

import org.openjdk.jmh.annotations.CompilerControl;

/**
 * The five escape-analysis variants. Every variant constructs one {@link
 * Aggregate} per element and must accumulate the IDENTICAL {@code
 * x[i]+y[i]} total (../fixtures/escape-analysis-scalar-replacement-fixtures.json)
 * — how the aggregate is used changes whether C2 can scalar-replace it,
 * never the computed result.
 */
public final class EscapeAnalysisOperations {

    private EscapeAnalysisOperations() {}

    /**
     * Passed-to-opaque-call barrier: {@code DONT_INLINE} so C2 cannot see
     * into this method from the caller's compilation unit — escape
     * analysis is fundamentally a property of what has been inlined
     * together, so a real (non-inlined) call boundary is the honest way
     * to defeat it, the same technique this lab's plab-202 prerequisite
     * uses to defeat range-check elimination.
     */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    static long opaqueSum(Aggregate a) {
        return a.sum();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static Aggregate opaqueBuild(long x, long y) {
        return new Aggregate(x, y);
    }

    /** Non-escaping: the aggregate is built, read locally and discarded — never leaves the method. */
    public static long sumNonEscaping(long[] x, long[] y) {
        long sum = 0;
        for (int i = 0; i < x.length; i++) {
            Aggregate a = new Aggregate(x[i], y[i]);
            sum += a.sum();
        }
        return sum;
    }

    /** Returned object: a helper method builds and returns the aggregate; the caller reads it. */
    public static long sumReturnedObject(long[] x, long[] y) {
        long sum = 0;
        for (int i = 0; i < x.length; i++) {
            Aggregate a = opaqueBuild(x[i], y[i]);
            sum += a.sum();
        }
        return sum;
    }

    /** Stored into field: the aggregate is written into a longer-lived holder — GlobalEscape. */
    public static long sumStoredIntoField(long[] x, long[] y, AggregateHolder holder) {
        long sum = 0;
        for (int i = 0; i < x.length; i++) {
            Aggregate a = new Aggregate(x[i], y[i]);
            holder.store(a);
            sum += holder.last().sum();
        }
        return sum;
    }

    /** Passed to opaque call: escape analysis cannot see across the un-inlined call boundary. */
    public static long sumPassedToOpaqueCall(long[] x, long[] y) {
        long sum = 0;
        for (int i = 0; i < x.length; i++) {
            Aggregate a = new Aggregate(x[i], y[i]);
            sum += opaqueSum(a);
        }
        return sum;
    }

    /**
     * Identity observed: {@code System.identityHashCode} is called for
     * its SIDE EFFECT only — forcing the object's identity to be
     * observable, which scalar replacement (no single address for
     * scalarized fields) cannot provide. The non-deterministic hash
     * value itself is discarded, never added to {@code sum}, so the
     * correctness oracle stays fully deterministic.
     */
    public static long sumIdentityObserved(long[] x, long[] y) {
        long sum = 0;
        for (int i = 0; i < x.length; i++) {
            Aggregate a = new Aggregate(x[i], y[i]);
            System.identityHashCode(a); // side effect only — never feeds into sum
            sum += a.sum();
        }
        return sum;
    }

    public static long run(String variant, long[] x, long[] y, AggregateHolder holder) {
        return switch (variant) {
            case "nonEscaping" -> sumNonEscaping(x, y);
            case "returnedObject" -> sumReturnedObject(x, y);
            case "storedIntoField" -> sumStoredIntoField(x, y, holder);
            case "passedToOpaqueCall" -> sumPassedToOpaqueCall(x, y);
            case "identityObserved" -> sumIdentityObserved(x, y);
            default -> throw new IllegalArgumentException("unknown variant: " + variant);
        };
    }
}
