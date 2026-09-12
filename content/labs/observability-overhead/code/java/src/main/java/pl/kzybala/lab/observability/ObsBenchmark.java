package pl.kzybala.lab.observability;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class ObsBenchmark {

    private ObsParams errorBurst;
    private ObsParams highCardinalityKey;
    private ObsParams stackTracePath;

    @Setup(Level.Trial)
    public void setup() {
        errorBurst = ObsFixtures.errorBurst();
        highCardinalityKey = ObsFixtures.highCardinalityKey();
        stackTracePath = ObsFixtures.stackTracePath();
    }

    @Benchmark
    public Outcome noInstrumentation() {
        return ObservabilityRunner.run(Variant.NO_INSTRUMENTATION, errorBurst);
    }

    @Benchmark
    public Outcome disabledEagerLogging() {
        return ObservabilityRunner.run(Variant.DISABLED_EAGER_LOGGING, errorBurst);
    }

    @Benchmark
    public Outcome disabledLazyLogging() {
        return ObservabilityRunner.run(Variant.DISABLED_LAZY_LOGGING, errorBurst);
    }

    @Benchmark
    public Outcome synchronousLogging() {
        return ObservabilityRunner.run(Variant.SYNCHRONOUS_LOGGING, errorBurst);
    }

    @Benchmark
    public Outcome asyncBoundedLogging() {
        return ObservabilityRunner.run(Variant.ASYNC_BOUNDED_LOGGING, errorBurst);
    }

    @Benchmark
    public Outcome metricsLabels() {
        return ObservabilityRunner.run(Variant.METRICS_LABELS, highCardinalityKey);
    }

    @Benchmark
    public Outcome sampledTracing() {
        return ObservabilityRunner.run(Variant.SAMPLED_TRACING, stackTracePath);
    }
}
