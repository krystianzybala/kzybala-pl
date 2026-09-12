package pl.kzybala.lab.inlining;

/**
 * A shared pool of six DISTINCT concrete implementations — the variable
 * this lab measures is how many of these a single call site sees at
 * runtime (monomorphic/bimorphic/megamorphic), never what each one
 * computes. Reused identically across all three named datasets
 * (pricingFunctions, codecStrategies, validationRules); see java.md for
 * why the strategy pool is deliberately shared rather than reimplemented
 * per dataset.
 */
public sealed interface LongStrategy
    permits AddOne, MulThree, XorMask, ShiftAddSeven, SubEleven, ShiftOrOne {
    long apply(long x);

    static LongStrategy[] pool() {
        return new LongStrategy[] {
            new AddOne(), new MulThree(), new XorMask(), new ShiftAddSeven(), new SubEleven(), new ShiftOrOne()
        };
    }
}

record AddOne() implements LongStrategy {
    public long apply(long x) { return x + 1; }
}

record MulThree() implements LongStrategy {
    public long apply(long x) { return x * 3; }
}

record XorMask() implements LongStrategy {
    public long apply(long x) { return x ^ 0x5A5AL; }
}

record ShiftAddSeven() implements LongStrategy {
    public long apply(long x) { return (x << 1) + 7; }
}

record SubEleven() implements LongStrategy {
    public long apply(long x) { return x - 11; }
}

record ShiftOrOne() implements LongStrategy {
    public long apply(long x) { return (x >> 2) | 1; }
}
