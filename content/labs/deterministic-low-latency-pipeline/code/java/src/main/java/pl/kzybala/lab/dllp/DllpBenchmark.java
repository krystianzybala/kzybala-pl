package pl.kzybala.lab.dllp;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class DllpBenchmark {

    private PipelineParams uniformBurst;
    private PipelineParams hotKeyBurst;

    @Setup(Level.Trial)
    public void setup() {
        uniformBurst = DllpFixtures.mediumEventsUniformBurst();
        hotKeyBurst = DllpFixtures.mediumEventsHotKeyBurst();
    }

    @Benchmark
    public Outcome naiveObjectQueue() {
        return PipelineSimulator.simulate(PipelineVariant.NAIVE_OBJECT_QUEUE, uniformBurst);
    }

    @Benchmark
    public Outcome optimized() {
        return PipelineSimulator.simulate(PipelineVariant.OPTIMIZED, uniformBurst);
    }

    @Benchmark
    public Outcome overloadProfile() {
        return PipelineSimulator.simulate(PipelineVariant.OVERLOAD_PROFILE, hotKeyBurst);
    }

    @Benchmark
    public Outcome faultRestartProfile() {
        return PipelineSimulator.simulate(PipelineVariant.FAULT_RESTART_PROFILE, hotKeyBurst);
    }
}
