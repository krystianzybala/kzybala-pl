package pl.kzybala.lab.coload;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CoLoadBenchmark {

    private LoadParams periodicStall;
    private LoadParams boundedOverload;

    @Setup(Level.Trial)
    public void setup() {
        periodicStall = CoLoadFixtures.periodicTenMsStall();
        boundedOverload = CoLoadFixtures.boundedServerOverload();
    }

    @Benchmark
    public Outcome closedLoop() {
        return LoadGenSimulator.simulate(Variant.CLOSED_LOOP, periodicStall);
    }

    @Benchmark
    public Outcome openLoopFixedRate() {
        return LoadGenSimulator.simulate(Variant.OPEN_LOOP_FIXED_RATE, periodicStall);
    }

    @Benchmark
    public Outcome poissonLikeArrivals() {
        return LoadGenSimulator.simulate(Variant.POISSON_LIKE_ARRIVALS, periodicStall);
    }

    @Benchmark
    public Outcome omissionCorrectedRecording() {
        return LoadGenSimulator.simulate(Variant.OMISSION_CORRECTED_RECORDING, periodicStall);
    }

    @Benchmark
    public Outcome burstSchedule() {
        return LoadGenSimulator.simulate(Variant.BURST_SCHEDULE, periodicStall);
    }

    @Benchmark
    public Outcome openLoopUnderSustainedOverload() {
        return LoadGenSimulator.simulate(Variant.OPEN_LOOP_FIXED_RATE, boundedOverload);
    }
}
