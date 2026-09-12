package pl.kzybala.lab.mpsc;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

/**
 * Companion benchmark for the "MPSC Queues and Producer Contention"
 * Performance Lab (kzybala.pl/lab/mpsc-contention/).
 *
 * <p>Each {@code @Benchmark} method invokes one fan-in variant with a
 * fixed producer count directly. Every kernel spawns and joins its own
 * producer/consumer threads internally, so every measured operation
 * includes real thread spawn/join overhead — a macro benchmark of the
 * full fan-in protocol, not a steady-state microbenchmark of one
 * isolated enqueue. See benchmark.md for the exact operation definition
 * and why these numbers are not directly comparable across variants
 * without controlling for that overhead.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class MpscBenchmark {

    // The correctness suite fixes producer counts to {2, 4}
    // (MpscFixtures.PRODUCER_COUNTS) for speed; the benchmark matrix
    // additionally covers 8, and the native-Linux evidence runner's
    // .conf derives the actual "physical-core producers" scenario from
    // the live, validated CPU set size at measurement time rather than
    // hardcoding a number here (see scripts/performance-lab/labs/
    // mpsc-contention.conf).
    @Param({"2", "4", "8"})
    public int producers;

    @Benchmark
    public MpscResult sharedMpsc() throws InterruptedException {
        return SharedMpscKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY);
    }

    @Benchmark
    public MpscResult batchedClaim() throws InterruptedException {
        return BatchedClaimKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY, MpscFixtures.BATCH_SIZE);
    }

    @Benchmark
    public MpscResult perProducerFanIn() throws InterruptedException {
        return PerProducerFanInKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY);
    }

    @Benchmark
    public MpscResult mutexQueue() throws InterruptedException {
        return MutexQueueKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY);
    }

    @Benchmark
    public MpscResult libraryQueue() throws InterruptedException {
        return LibraryQueueKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER);
    }
}
