package pl.kzybala.lab.inlining;

/**
 * The manual switch/enum-dispatch alternative to {@link LongStrategy}'s
 * interface polymorphism — the same six transforms, selected by a closed
 * {@code switch} instead of a virtual/interface call. No vtable, no
 * inline-cache staging: a {@code switch} over an enum compiles to a
 * direct jump table or a chain of integer compares, which C2 can inline
 * and optimize through regardless of how "diverse" the call site's
 * inputs are — the point of the {@code switchDispatch} variant
 * (theory.md).
 */
public enum StrategyKind {
    ADD_ONE, MUL_THREE, XOR_MASK, SHIFT_ADD_SEVEN, SUB_ELEVEN, SHIFT_OR_ONE;

    public static StrategyKind forIndex(int i) {
        return switch (i) {
            case 0 -> ADD_ONE;
            case 1 -> MUL_THREE;
            case 2 -> XOR_MASK;
            case 3 -> SHIFT_ADD_SEVEN;
            case 4 -> SUB_ELEVEN;
            case 5 -> SHIFT_OR_ONE;
            default -> throw new IllegalArgumentException("no strategy at index " + i);
        };
    }

    public long apply(long x) {
        return switch (this) {
            case ADD_ONE -> x + 1;
            case MUL_THREE -> x * 3;
            case XOR_MASK -> x ^ 0x5A5AL;
            case SHIFT_ADD_SEVEN -> (x << 1) + 7;
            case SUB_ELEVEN -> x - 11;
            case SHIFT_OR_ONE -> (x >> 2) | 1;
        };
    }
}
