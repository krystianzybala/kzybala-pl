package pl.kzybala.lab.deoptimization;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Companion dev/wiring benchmark for the "Deoptimization and uncommon
 * traps" Performance Lab
 * (kzybala.pl/lab/deoptimization-uncommon-traps/): aggregate throughput
 * of {@link DeoptOperations#run} over the strategy-dispatch dataset —
 * wiring/correctness smoke only. JMH's steady-state averaging cannot
 * represent this lab's actual phenomenon (a one-time regime shift mid-
 * run); the real evidence is {@link DeoptTimelineHarness}, run directly
 * (java.md) and through the native-Linux runner as an aux-kind harness
 * (see benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class DeoptBenchmark {

    @Param({"stableTypeProfile", "profileShiftAfterWarmup", "rareExceptionPath", "lateSubtypeLoading", "nullabilityShift"})
    public String variant;

    private long[] inputs;

    @Setup(Level.Trial)
    public void setup() {
        inputs = DeoptFixtures.strategyDispatch(DeoptFixtures.N);
    }

    /** One operation = one full pass over 1,000,000 elements (aggregate throughput only — no timeline). */
    @Benchmark
    public long sum() {
        return DeoptOperations.run(variant, inputs);
    }
}
