package pl.kzybala.lab.backpressure;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class BpBenchmark {

    private PipelineParams sustainedOverload;
    private PipelineParams hotKeySkew;

    @Setup(Level.Trial)
    public void setup() {
        sustainedOverload = BpFixtures.sustainedOverload();
        hotKeySkew = BpFixtures.hotKeySkew();
    }

    @Benchmark
    public SimResult unbounded() {
        return PipelineSimulator.simulate(Policy.UNBOUNDED, sustainedOverload);
    }

    @Benchmark
    public SimResult boundedReject() {
        return PipelineSimulator.simulate(Policy.BOUNDED_REJECT, sustainedOverload);
    }

    @Benchmark
    public SimResult boundedBlock() {
        return PipelineSimulator.simulate(Policy.BOUNDED_BLOCK, sustainedOverload);
    }

    @Benchmark
    public SimResult dropOldest() {
        return PipelineSimulator.simulate(Policy.DROP_OLDEST, sustainedOverload);
    }

    @Benchmark
    public SimResult coalesceByKey() {
        return PipelineSimulator.simulate(Policy.COALESCE_BY_KEY, hotKeySkew);
    }

    @Benchmark
    public SimResult loadShedding() {
        return PipelineSimulator.simulate(Policy.LOAD_SHEDDING, sustainedOverload);
    }
}
