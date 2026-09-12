package pl.kzybala.lab.deoptimization;

/**
 * The five speculative-assumption variants. Every variant processes
 * {@link DeoptFixtures#N} inputs and must accumulate the fixture-pinned
 * total (../fixtures/deoptimization-uncommon-traps-fixtures.json) — WHEN
 * and HOW the underlying type/exception/null pattern shifts changes cost
 * and latency shape, never the final total. This class computes totals
 * only (the correctness-checked path, no timing); {@link
 * DeoptTimelineHarness} is where the actual per-call latency evidence
 * comes from.
 */
public final class DeoptOperations {

    private DeoptOperations() {}

    /** stableTypeProfile: typeA for every index — the control variant, no shift ever occurs. */
    public static long sumStableTypeProfile(long[] inputs) {
        OperationStrategy a = new TypeA();
        long sum = 0;
        for (long v : inputs) sum += a.apply(v);
        return sum;
    }

    /** profileShiftAfterWarmup: typeA only before shiftPoint; typeA/typeB alternating after. */
    public static long sumProfileShiftAfterWarmup(long[] inputs) {
        OperationStrategy a = new TypeA();
        OperationStrategy b = new TypeB();
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            OperationStrategy s;
            if (i < DeoptFixtures.SHIFT_POINT) {
                s = a;
            } else {
                s = (i % 2 == 0) ? a : b;
            }
            sum += s.apply(inputs[i]);
        }
        return sum;
    }

    /**
     * rareExceptionPath: a genuine exception is thrown and caught at
     * exactly one deterministic index — HotSpot's uncommon-trap
     * mechanism specifically targets exception paths a compiled method
     * assumed, from its warm-up profile, would never execute.
     */
    public static long sumRareExceptionPath(long[] inputs) {
        OperationStrategy a = new TypeA();
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            try {
                if (i == DeoptFixtures.RARE_EXCEPTION_INDEX) {
                    throw new ArithmeticException("deliberate, deterministic rare-path exception at index " + i);
                }
                sum += a.apply(inputs[i]);
            } catch (ArithmeticException e) {
                sum += DeoptFixtures.FALLBACK_EXCEPTION;
            }
        }
        return sum;
    }

    /**
     * lateSubtypeLoading: typeA before shiftPoint; typeC — never before
     * constructed — from shiftPoint onward.
     */
    public static long sumLateSubtypeLoading(long[] inputs) {
        OperationStrategy a = new TypeA();
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            if (i < DeoptFixtures.SHIFT_POINT) {
                sum += a.apply(inputs[i]);
            } else {
                // TypeC's first-ever construction happens right here, at
                // i == SHIFT_POINT — its class was never loaded/linked
                // before this point in the run.
                OperationStrategy c = new TypeC();
                sum += c.apply(inputs[i]);
            }
        }
        return sum;
    }

    /**
     * nullabilityShift: never null before shiftPoint; a null-marker
     * input at every {@code nullStride}-th index from shiftPoint onward.
     */
    public static long sumNullabilityShift(long[] inputs) {
        OperationStrategy a = new TypeA();
        long sum = 0;
        for (int i = 0; i < inputs.length; i++) {
            Long boxed = boxedInputFor(inputs, i);
            sum += (boxed == null) ? DeoptFixtures.FALLBACK_NULL : a.apply(boxed);
        }
        return sum;
    }

    private static Long boxedInputFor(long[] inputs, int i) {
        if (i >= DeoptFixtures.SHIFT_POINT && (i - DeoptFixtures.SHIFT_POINT) % DeoptFixtures.NULL_STRIDE == 0) {
            return null;
        }
        return inputs[i];
    }

    public static long run(String variant, long[] inputs) {
        return switch (variant) {
            case "stableTypeProfile" -> sumStableTypeProfile(inputs);
            case "profileShiftAfterWarmup" -> sumProfileShiftAfterWarmup(inputs);
            case "rareExceptionPath" -> sumRareExceptionPath(inputs);
            case "lateSubtypeLoading" -> sumLateSubtypeLoading(inputs);
            case "nullabilityShift" -> sumNullabilityShift(inputs);
            default -> throw new IllegalArgumentException("unknown variant: " + variant);
        };
    }
}
