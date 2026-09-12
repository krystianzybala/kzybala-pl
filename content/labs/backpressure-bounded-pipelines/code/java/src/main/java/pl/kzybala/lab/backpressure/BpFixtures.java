package pl.kzybala.lab.backpressure;

import java.util.stream.IntStream;

/** Hardcoded mirror of code/fixtures/backpressure-bounded-pipelines-fixtures.json. */
public final class BpFixtures {
    private BpFixtures() {}

    private static int[] constant(int perTick, int ticks) {
        return IntStream.range(0, ticks).map(i -> perTick).toArray();
    }

    private static int[] burstThenDrain(int burstPerTick, int burstTicks, int drainTicks) {
        int[] schedule = new int[burstTicks + drainTicks];
        for (int i = 0; i < burstTicks; i++) schedule[i] = burstPerTick;
        return schedule;
    }

    /** Producer rate == consumer rate; queue never grows. */
    public static PipelineParams steadyBelowCapacity() {
        return new PipelineParams(8, constant(4, 200), 4, 4, 50);
    }

    /** A short burst well above the bounded capacity, followed by a long drain-only tail. */
    public static PipelineParams shortBurst() {
        return new PipelineParams(8, burstThenDrain(20, 5, 200), 4, 4, 50);
    }

    /** Producer sustained above consumer rate for a long window, then a long drain-only tail. */
    public static PipelineParams sustainedOverload() {
        return new PipelineParams(8, burstThenDrain(10, 50, 400), 4, 4, 50);
    }

    /** Same overload shape, but a small key space so coalescing has repeated keys to collapse. */
    public static PipelineParams hotKeySkew() {
        return new PipelineParams(8, burstThenDrain(10, 50, 400), 4, 3, 50);
    }
}
