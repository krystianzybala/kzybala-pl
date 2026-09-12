package pl.kzybala.lab.arenareuse;

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

import static pl.kzybala.lab.arenareuse.ArenaReuseFixtures.MessageSource;

/**
 * Companion dev/wiring benchmark for the "Arena lifetimes, pools and
 * reuse" Performance Lab (kzybala.pl/lab/arena-lifetimes-reuse/): the
 * five lifecycle variants over the {@code messageBatches} dataset only,
 * unpinned — for local smoke and IDE profiling. Publication evidence
 * comes exclusively from {@link ArenaReuseLinuxEvidenceBenchmark} via
 * the native-Linux runner (see benchmark.md).
 *
 * <p>{@code fullPass} times one complete pass over the whole dataset
 * (B/op, allocations/op via {@code -prof gc}); {@code oneBatchLatency}
 * (SampleTime, overriding the class default) times exactly ONE batch's
 * worth of work — the natural unit for {@code batchArena}'s reset/close
 * cost and for this lab's required p99 metric, which JMH's SampleTime
 * mode reports natively as a percentile distribution.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ArenaReuseBenchmark {

    @Param({"allocatePerItem", "batchArena", "threadLocalReuse", "globalPool", "boundedPool"})
    public String variant;

    private MessageSource fullSource;
    private MessageSource oneBatchSource;
    private UnboundedPool<MessageScratch> unboundedPool;
    private BoundedPool<MessageScratch> boundedPoolInstance;

    @Setup(Level.Trial)
    public void setup() {
        fullSource = ArenaReuseFixtures.generateMessages(ArenaReuseFixtures.MESSAGE_N);
        MessageSource full = ArenaReuseFixtures.generateMessages(ArenaReuseFixtures.MESSAGE_BATCH_SIZE);
        oneBatchSource = full;
        unboundedPool = new UnboundedPool<>(MessageScratch::new);
        boundedPoolInstance = new BoundedPool<>(MessageBatchOperations.POOL_CAPACITY, MessageScratch::new);
    }

    private long run(MessageSource source, int batchSize) {
        return switch (variant) {
            case "allocatePerItem" -> MessageBatchOperations.allocatePerItem(source);
            case "batchArena" -> MessageBatchOperations.batchArena(source, batchSize);
            case "threadLocalReuse" -> MessageBatchOperations.threadLocalReuse(source);
            case "globalPool" -> MessageBatchOperations.globalPool(source, unboundedPool);
            case "boundedPool" -> MessageBatchOperations.boundedPool(source, boundedPoolInstance);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    @Benchmark
    public long fullPass() {
        return run(fullSource, ArenaReuseFixtures.MESSAGE_BATCH_SIZE);
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public long oneBatchLatency() {
        return run(oneBatchSource, ArenaReuseFixtures.MESSAGE_BATCH_SIZE);
    }
}
