package pl.kzybala.lab.deoptimization;

/** Three concrete implementations — the types a call site's runtime profile can shift across. */
public interface OperationStrategy {
    long apply(long v);
}

final class TypeA implements OperationStrategy {
    public long apply(long v) { return v + 1; }
}

final class TypeB implements OperationStrategy {
    public long apply(long v) { return v * 3; }
}

/**
 * Never constructed before {@code shiftPoint} in the {@code
 * lateSubtypeLoading} variant — its first instantiation, deep into an
 * otherwise-stable run, is the point HotSpot's class-hierarchy-analysis
 * assumptions can be invalidated.
 */
final class TypeC implements OperationStrategy {
    public long apply(long v) { return v ^ 0x5A5AL; }
}
