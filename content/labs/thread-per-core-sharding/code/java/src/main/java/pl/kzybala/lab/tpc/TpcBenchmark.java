package pl.kzybala.lab.tpc;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "Thread-per-Core and Shared-Nothing
 * Sharding" Performance Lab (kzybala.pl/lab/thread-per-core-sharding/).
 *
 * <p>Each {@code @Benchmark} method invokes one mechanism/dataset pair
 * directly. Every kernel spawns and joins its own worker threads
 * internally, so every measured operation is a macro measurement of the
 * full request set running to completion — see benchmark.md.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class TpcBenchmark {

    @Benchmark
    public TpcResult sharedMapUniform() throws InterruptedException {
        return SharedMapKernel.run(WorkloadPlan.uniform());
    }

    @Benchmark
    public TpcResult sharedMapSkewed() throws InterruptedException {
        return SharedMapKernel.run(WorkloadPlan.skewed());
    }

    @Benchmark
    public TpcResult mutexShardsUniform() throws InterruptedException {
        return MutexShardsKernel.run(WorkloadPlan.uniform());
    }

    @Benchmark
    public TpcResult mutexShardsSkewed() throws InterruptedException {
        return MutexShardsKernel.run(WorkloadPlan.skewed());
    }

    @Benchmark
    public TpcResult singleWriterUniform() throws InterruptedException {
        return SingleWriterKernel.run(WorkloadPlan.uniform());
    }

    @Benchmark
    public TpcResult singleWriterSkewed() throws InterruptedException {
        return SingleWriterKernel.run(WorkloadPlan.skewed());
    }

    @Benchmark
    public TpcResult rebalanceSimulation() throws InterruptedException {
        return RebalanceSimulationKernel.run();
    }
}
