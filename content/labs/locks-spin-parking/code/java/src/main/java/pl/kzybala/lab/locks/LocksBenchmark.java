package pl.kzybala.lab.locks;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "Locks, Spin Waiting and Parking"
 * Performance Lab (kzybala.pl/lab/locks-spin-parking/).
 *
 * <p>Each {@code @Benchmark} method invokes one wait-strategy variant
 * directly. Every kernel spawns and joins its own worker threads
 * internally, so every measured operation is a macro measurement of the
 * full worker set running to completion, not a steady-state
 * microbenchmark of one isolated lock/unlock pair — see benchmark.md.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class LocksBenchmark {

    @Benchmark
    public LockResult uncontendedMutex() throws InterruptedException {
        return MutexKernel.uncontended();
    }

    @Benchmark
    public LockResult shortContended() throws InterruptedException {
        return MutexKernel.shortContended(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER);
    }

    @Benchmark
    public LockResult longCriticalSection() throws InterruptedException {
        return MutexKernel.longCriticalSection(
                LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER, LocksFixtures.LONG_CS_BUSY_ITERATIONS);
    }

    @Benchmark
    public LockResult casLoop() throws InterruptedException {
        return CasLoopKernel.run(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER);
    }

    @Benchmark
    public LockResult spinThenPark() throws InterruptedException {
        return SpinThenParkKernel.run(LocksFixtures.WORKER_COUNT, LocksFixtures.OPS_PER_WORKER, LocksFixtures.SPIN_LIMIT);
    }
}
