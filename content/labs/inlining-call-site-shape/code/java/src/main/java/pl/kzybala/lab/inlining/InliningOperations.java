package pl.kzybala.lab.inlining;

/**
 * The five call-site-shape variants, each consuming any of the three
 * datasets' input arrays (the strategy pool is dataset-agnostic — see
 * java.md). Every method's one operation is a single pass over the
 * inputs, dispatching to a strategy per element and accumulating a
 * wrapping {@code long} sum; the returned sum is consumed so it cannot
 * be eliminated as dead code.
 */
public final class InliningOperations {

    private InliningOperations() {}

    /** Monomorphic: the call site only ever sees {@link AddOne}. */
    public static long sumMonomorphic(long[] inputs) {
        LongStrategy strategy = new AddOne();
        long sum = 0;
        for (long x : inputs) sum += strategy.apply(x);
        return sum;
    }

    /** Bimorphic: the call site alternates between exactly two concrete types. */
    public static long sumBimorphic(long[] inputs) {
        LongStrategy[] pair = { new AddOne(), new MulThree() };
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            sum += pair[i % 2].apply(inputs[i]);
        }
        return sum;
    }

    /** Megamorphic: the call site round-robins all six concrete types. */
    public static long sumMegamorphic(long[] inputs) {
        LongStrategy[] pool = LongStrategy.pool();
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            sum += pool[i % 6].apply(inputs[i]);
        }
        return sum;
    }

    /**
     * Manual switch/enum dispatch: the IDENTICAL six-way round-robin as
     * {@link #sumMegamorphic}, but through a closed {@code switch} —
     * never a virtual/interface call, so there is no inline-cache
     * staging to defeat in the first place.
     */
    public static long sumSwitchDispatch(long[] inputs) {
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            sum += StrategyKind.forIndex(i % 6).apply(inputs[i]);
        }
        return sum;
    }

    /**
     * Oversized callee: monomorphic call-site shape (always the same
     * logical strategy — computes {@code x+1}, identical to {@link
     * AddOne}), but the callee's bytecode is deliberately inflated well
     * past HotSpot's inlining size thresholds (MaxInlineSize=35,
     * FreqInlineSize=325 bytecodes by default) with arithmetic that
     * cancels out. Call-site shape is not the only inlining blocker —
     * this variant isolates the other one (benchmark.md).
     */
    public static long sumOversizedCallee(long[] inputs) {
        long sum = 0;
        for (long v : inputs) sum += bigAddOne(v);
        return sum;
    }

    private static long bigAddOne(long x) {
        x = x + 1L;
        x = x - 1L;
        x = x + 2L;
        x = x - 2L;
        x = x + 3L;
        x = x - 3L;
        x = x + 4L;
        x = x - 4L;
        x = x + 5L;
        x = x - 5L;
        x = x + 6L;
        x = x - 6L;
        x = x + 7L;
        x = x - 7L;
        x = x + 8L;
        x = x - 8L;
        x = x + 9L;
        x = x - 9L;
        x = x + 10L;
        x = x - 10L;
        x = x + 11L;
        x = x - 11L;
        x = x + 12L;
        x = x - 12L;
        x = x + 13L;
        x = x - 13L;
        x = x + 14L;
        x = x - 14L;
        x = x + 15L;
        x = x - 15L;
        x = x + 16L;
        x = x - 16L;
        x = x + 17L;
        x = x - 17L;
        x = x + 18L;
        x = x - 18L;
        x = x + 19L;
        x = x - 19L;
        x = x + 20L;
        x = x - 20L;
        x = x + 21L;
        x = x - 21L;
        x = x + 22L;
        x = x - 22L;
        x = x + 23L;
        x = x - 23L;
        x = x + 24L;
        x = x - 24L;
        x = x + 25L;
        x = x - 25L;
        x = x + 26L;
        x = x - 26L;
        x = x + 27L;
        x = x - 27L;
        x = x + 28L;
        x = x - 28L;
        x = x + 29L;
        x = x - 29L;
        x = x + 30L;
        x = x - 30L;
        x = x + 31L;
        x = x - 31L;
        x = x + 32L;
        x = x - 32L;
        x = x + 33L;
        x = x - 33L;
        x = x + 34L;
        x = x - 34L;
        x = x + 35L;
        x = x - 35L;
        x = x + 36L;
        x = x - 36L;
        x = x + 37L;
        x = x - 37L;
        x = x + 38L;
        x = x - 38L;
        x = x + 39L;
        x = x - 39L;
        x = x + 40L;
        x = x - 40L;
        x = x + 41L;
        x = x - 41L;
        x = x + 42L;
        x = x - 42L;
        x = x + 43L;
        x = x - 43L;
        x = x + 44L;
        x = x - 44L;
        x = x + 45L;
        x = x - 45L;
        x = x + 46L;
        x = x - 46L;
        x = x + 47L;
        x = x - 47L;
        x = x + 48L;
        x = x - 48L;
        x = x + 49L;
        x = x - 49L;
        x = x + 50L;
        x = x - 50L;
        x = x + 51L;
        x = x - 51L;
        x = x + 52L;
        x = x - 52L;
        x = x + 53L;
        x = x - 53L;
        x = x + 54L;
        x = x - 54L;
        x = x + 55L;
        x = x - 55L;
        x = x + 56L;
        x = x - 56L;
        x = x + 57L;
        x = x - 57L;
        x = x + 58L;
        x = x - 58L;
        x = x + 59L;
        x = x - 59L;
        x = x + 60L;
        x = x - 60L;
        x = x + 61L;
        x = x - 61L;
        x = x + 62L;
        x = x - 62L;
        x = x + 63L;
        x = x - 63L;
        x = x + 64L;
        x = x - 64L;
        x = x + 65L;
        x = x - 65L;
        x = x + 66L;
        x = x - 66L;
        x = x + 67L;
        x = x - 67L;
        x = x + 68L;
        x = x - 68L;
        x = x + 69L;
        x = x - 69L;
        x = x + 70L;
        x = x - 70L;
        x = x + 71L;
        x = x - 71L;
        x = x + 72L;
        x = x - 72L;
        x = x + 73L;
        x = x - 73L;
        x = x + 74L;
        x = x - 74L;
        x = x + 75L;
        x = x - 75L;
        x = x + 76L;
        x = x - 76L;
        x = x + 77L;
        x = x - 77L;
        x = x + 78L;
        x = x - 78L;
        x = x + 79L;
        x = x - 79L;
        x = x + 80L;
        x = x - 80L;
        return x + 1L;
    }

    public static long run(String variant, long[] inputs) {
        return switch (variant) {
            case "monomorphic" -> sumMonomorphic(inputs);
            case "bimorphic" -> sumBimorphic(inputs);
            case "megamorphic" -> sumMegamorphic(inputs);
            case "switchDispatch" -> sumSwitchDispatch(inputs);
            case "oversizedCallee" -> sumOversizedCallee(inputs);
            default -> throw new IllegalArgumentException("unknown variant: " + variant);
        };
    }
}
