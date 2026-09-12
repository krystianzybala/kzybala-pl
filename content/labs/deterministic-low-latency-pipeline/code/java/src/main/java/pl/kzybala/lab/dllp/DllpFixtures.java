package pl.kzybala.lab.dllp;

import java.util.stream.IntStream;

/** Hardcoded mirror of code/fixtures/deterministic-low-latency-pipeline-fixtures.json. */
public final class DllpFixtures {
    private DllpFixtures() {}

    private static int[] constant(int perTick, int ticks) {
        return IntStream.range(0, ticks).map(i -> perTick).toArray();
    }

    private static int[] burstThenDrain(int burstPerTick, int burstTicks, int drainTicks) {
        int[] schedule = new int[burstTicks + drainTicks];
        for (int i = 0; i < burstTicks; i++) schedule[i] = burstPerTick;
        return schedule;
    }

    public static PipelineParams smallEventsUniformSteady() {
        return new PipelineParams(32, KeyPattern.UNIFORM, constant(5, 300), 4, 50, 5, 5, 0);
    }

    public static PipelineParams largeEventsUniformSteady() {
        return new PipelineParams(1024, KeyPattern.UNIFORM, constant(5, 300), 4, 50, 5, 5, 0);
    }

    public static PipelineParams mediumEventsHotKeyBurst() {
        return new PipelineParams(128, KeyPattern.HOT_KEY_SKEW, burstThenDrain(40, 10, 100), 4, 50, 5, 5, 0);
    }

    public static PipelineParams mediumEventsUniformBurst() {
        return new PipelineParams(128, KeyPattern.UNIFORM, burstThenDrain(40, 10, 100), 4, 50, 5, 5, 0);
    }
}
