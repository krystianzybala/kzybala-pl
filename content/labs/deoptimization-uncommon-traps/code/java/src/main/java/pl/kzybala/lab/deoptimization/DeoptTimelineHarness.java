package pl.kzybala.lab.deoptimization;

import java.util.Arrays;

/**
 * The real evidence harness for this lab: JMH's steady-state averaging
 * would misrepresent a ONE-TIME regime shift in the middle of a run —
 * exactly the "hiding warm-up phase transitions" and "publishing only
 * steady-state mean" traps this lab exists to avoid (benchmark.md) — so
 * this is a dedicated, {@code main()}-based aux harness (the same
 * category of tool content/labs/clocks-latency-histograms uses for
 * distribution mechanics a throughput mode would distort). One pass
 * over {@link DeoptFixtures#N} inputs is timed in fixed-size batches
 * (never per-call — a single-arithmetic-op call is far smaller than
 * {@code nanoTime}'s own resolution and cost; batching is the same
 * "instrument is part of the experiment" discipline as the
 * clocks-latency-histograms lab this one's prerequisite chain builds on)
 * and split into three windows: pre-shift (steady, excluding early JIT
 * warm-up), the shift window immediately after {@link
 * DeoptFixtures#SHIFT_POINT} (where a genuine deopt/recompile stall
 * would appear as one anomalous batch), and post-shift-steady.
 */
public final class DeoptTimelineHarness {

    static final int BATCH_SIZE = 100;
    static final int WARMUP_EXCLUDE_CALLS = 50_000; // excluded from the pre-shift window
    static final int SHIFT_WINDOW_CALLS = 10_000; // batches immediately after SHIFT_POINT

    private DeoptTimelineHarness() {}

    record Percentiles(long p50, long p99, long p999, long max, int count) {
        static Percentiles of(long[] sortedBatchNanos) {
            int n = sortedBatchNanos.length;
            if (n == 0) return new Percentiles(0, 0, 0, 0, 0);
            return new Percentiles(
                sortedBatchNanos[(int) (n * 0.50)],
                sortedBatchNanos[Math.min(n - 1, (int) (n * 0.99))],
                sortedBatchNanos[Math.min(n - 1, (int) (n * 0.999))],
                sortedBatchNanos[n - 1],
                n);
        }
    }

    private interface BatchOp {
        long apply(int index, long value);
    }

    private static long timedRun(long[] inputs, long[] batchNanos, BatchOp op) {
        long sum = 0;
        int batches = inputs.length / BATCH_SIZE;
        int idx = 0;
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            for (int k = 0; k < BATCH_SIZE; k++, idx++) {
                sum += op.apply(idx, inputs[idx]);
            }
            batchNanos[b] = System.nanoTime() - start;
        }
        return sum;
    }

    static long timedStableTypeProfile(long[] inputs, long[] batchNanos) {
        OperationStrategy a = new TypeA();
        return timedRun(inputs, batchNanos, (i, v) -> a.apply(v));
    }

    static long timedProfileShiftAfterWarmup(long[] inputs, long[] batchNanos) {
        OperationStrategy a = new TypeA();
        OperationStrategy b = new TypeB();
        return timedRun(inputs, batchNanos, (i, v) -> {
            if (i < DeoptFixtures.SHIFT_POINT) return a.apply(v);
            return (i % 2 == 0) ? a.apply(v) : b.apply(v);
        });
    }

    static long timedRareExceptionPath(long[] inputs, long[] batchNanos) {
        OperationStrategy a = new TypeA();
        return timedRun(inputs, batchNanos, (i, v) -> {
            try {
                if (i == DeoptFixtures.RARE_EXCEPTION_INDEX) {
                    throw new ArithmeticException("deliberate, deterministic rare-path exception at index " + i);
                }
                return a.apply(v);
            } catch (ArithmeticException e) {
                return DeoptFixtures.FALLBACK_EXCEPTION;
            }
        });
    }

    static long timedLateSubtypeLoading(long[] inputs, long[] batchNanos) {
        OperationStrategy a = new TypeA();
        return timedRun(inputs, batchNanos, (i, v) -> {
            if (i < DeoptFixtures.SHIFT_POINT) return a.apply(v);
            return new TypeC().apply(v);
        });
    }

    static long timedNullabilityShift(long[] inputs, long[] batchNanos) {
        OperationStrategy a = new TypeA();
        return timedRun(inputs, batchNanos, (i, v) -> {
            boolean isNull = i >= DeoptFixtures.SHIFT_POINT && (i - DeoptFixtures.SHIFT_POINT) % DeoptFixtures.NULL_STRIDE == 0;
            return isNull ? DeoptFixtures.FALLBACK_NULL : a.apply(v);
        });
    }

    static long timedRun(String variant, long[] inputs, long[] batchNanos) {
        return switch (variant) {
            case "stableTypeProfile" -> timedStableTypeProfile(inputs, batchNanos);
            case "profileShiftAfterWarmup" -> timedProfileShiftAfterWarmup(inputs, batchNanos);
            case "rareExceptionPath" -> timedRareExceptionPath(inputs, batchNanos);
            case "lateSubtypeLoading" -> timedLateSubtypeLoading(inputs, batchNanos);
            case "nullabilityShift" -> timedNullabilityShift(inputs, batchNanos);
            default -> throw new IllegalArgumentException("unknown variant: " + variant);
        };
    }

    public static void main(String[] args) {
        String variant = "stableTypeProfile";
        String dataset = "strategyDispatch";
        for (int i = 0; i < args.length - 1; i++) {
            if ("--variant".equals(args[i])) variant = args[i + 1];
            if ("--dataset".equals(args[i])) dataset = args[i + 1];
        }

        long[] inputs = DeoptFixtures.inputsFor(dataset, DeoptFixtures.N);
        int batches = inputs.length / BATCH_SIZE;
        long[] batchNanos = new long[batches];

        long actual = timedRun(variant, inputs, batchNanos);
        long expected = DeoptOperations.run(variant, inputs);
        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }

        int warmupBatches = WARMUP_EXCLUDE_CALLS / BATCH_SIZE;
        int shiftBatchStart = DeoptFixtures.SHIFT_POINT / BATCH_SIZE;
        int shiftWindowBatches = SHIFT_WINDOW_CALLS / BATCH_SIZE;

        long[] preShift = Arrays.copyOfRange(batchNanos, warmupBatches, shiftBatchStart);
        long[] shiftWindow = Arrays.copyOfRange(batchNanos, shiftBatchStart, Math.min(batches, shiftBatchStart + shiftWindowBatches));
        long[] postShift = Arrays.copyOfRange(batchNanos, Math.min(batches, shiftBatchStart + shiftWindowBatches), batches);

        long[] preSorted = preShift.clone();
        long[] postSorted = postShift.clone();
        Arrays.sort(preSorted);
        Arrays.sort(postSorted);
        Percentiles pre = Percentiles.of(preSorted);
        Percentiles post = Percentiles.of(postSorted);
        long spikeMax = 0;
        for (long v : shiftWindow) spikeMax = Math.max(spikeMax, v);

        System.out.printf(
            "{%n  \"harness\": \"DeoptTimelineHarness\",%n  \"variant\": \"%s\",%n  \"dataset\": \"%s\",%n"
                + "  \"batchSize\": %d,%n  \"totalBatches\": %d,%n  \"checksum\": %d,%n"
                + "  \"preShift\": { \"batches\": %d, \"p50Ns\": %d, \"p99Ns\": %d, \"p999Ns\": %d, \"maxNs\": %d },%n"
                + "  \"shiftWindow\": { \"batches\": %d, \"maxNs\": %d },%n"
                + "  \"postShift\": { \"batches\": %d, \"p50Ns\": %d, \"p99Ns\": %d, \"p999Ns\": %d, \"maxNs\": %d }%n}%n",
            variant, dataset, BATCH_SIZE, batches, actual,
            pre.count(), pre.p50(), pre.p99(), pre.p999(), pre.max(),
            shiftWindow.length, spikeMax,
            post.count(), post.p50(), post.p99(), post.p999(), post.max());
    }
}
