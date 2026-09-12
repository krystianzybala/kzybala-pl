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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 5×3
 * matrix. Dataset generation and the correctness oracle both run in
 * setup, never in the measured method. {@code fullPass} (AverageTime)
 * gives B/op and allocations/op via {@code -prof gc}; {@code
 * oneBatchLatency} (SampleTime) gives the p99 this lab requires,
 * naturally, from JMH's own percentile reporting.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ArenaReuseLinuxEvidenceBenchmark {

    @Param({"allocatePerItem", "batchArena", "threadLocalReuse", "globalPool", "boundedPool"})
    public String variant;

    @Param({"messageBatches", "temporaryParseTrees", "scratchBuffers"})
    public String dataset;

    private WorkerPin pin;

    private ArenaReuseFixtures.MessageSource messagesFull;
    private ArenaReuseFixtures.MessageSource messagesOneBatch;
    private UnboundedPool<MessageScratch> messagePoolUnbounded;
    private BoundedPool<MessageScratch> messagePoolBounded;

    private long[] treesFull;
    private long[] treesOneBatch;
    private UnboundedPool<long[]> treePoolUnbounded;
    private BoundedPool<long[]> treePoolBounded;

    private long[] buffersFull;
    private long[] buffersOneBatch;
    private UnboundedPool<byte[]> bufferPoolUnbounded;
    private BoundedPool<byte[]> bufferPoolBounded;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }

        long expectedFull;
        long actualFull;
        switch (dataset) {
            case "messageBatches" -> {
                messagesFull = ArenaReuseFixtures.generateMessages(ArenaReuseFixtures.MESSAGE_N);
                messagesOneBatch = ArenaReuseFixtures.generateMessages(ArenaReuseFixtures.MESSAGE_BATCH_SIZE);
                messagePoolUnbounded = new UnboundedPool<>(MessageScratch::new);
                messagePoolBounded = new BoundedPool<>(MessageBatchOperations.POOL_CAPACITY, MessageScratch::new);
                expectedFull = ArenaReuseFixtures.expectedMessagesChecksum(messagesFull);
                actualFull = runMessages(messagesFull);
            }
            case "temporaryParseTrees" -> {
                treesFull = ArenaReuseFixtures.generateTrees(ArenaReuseFixtures.DOC_COUNT, ArenaReuseFixtures.NODES_PER_DOC);
                treesOneBatch = ArenaReuseFixtures.generateTrees(ArenaReuseFixtures.DOCS_PER_BATCH, ArenaReuseFixtures.NODES_PER_DOC);
                treePoolUnbounded = new UnboundedPool<>(() -> new long[ArenaReuseFixtures.NODES_PER_DOC]);
                treePoolBounded =
                    new BoundedPool<>(ParseTreeOperations.POOL_CAPACITY, () -> new long[ArenaReuseFixtures.NODES_PER_DOC]);
                expectedFull = ArenaReuseFixtures.expectedTreesChecksum(treesFull);
                actualFull = runTrees(treesFull, ArenaReuseFixtures.DOCS_PER_BATCH);
            }
            case "scratchBuffers" -> {
                buffersFull = ArenaReuseFixtures.generateBuffers(ArenaReuseFixtures.BUFFER_OP_COUNT, ArenaReuseFixtures.WORDS_PER_OP);
                buffersOneBatch = ArenaReuseFixtures.generateBuffers(ArenaReuseFixtures.OPS_PER_BATCH, ArenaReuseFixtures.WORDS_PER_OP);
                bufferPoolUnbounded = new UnboundedPool<>(() -> new byte[ArenaReuseFixtures.WORDS_PER_OP * 8]);
                bufferPoolBounded =
                    new BoundedPool<>(ScratchBufferOperations.POOL_CAPACITY, () -> new byte[ArenaReuseFixtures.WORDS_PER_OP * 8]);
                expectedFull = ArenaReuseFixtures.expectedBuffersChecksum(buffersFull);
                actualFull = runBuffers(buffersFull, ArenaReuseFixtures.OPS_PER_BATCH);
            }
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        }
        if (actualFull != expectedFull) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expectedFull + " got " + actualFull);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    private long runMessages(ArenaReuseFixtures.MessageSource source) {
        return switch (variant) {
            case "allocatePerItem" -> MessageBatchOperations.allocatePerItem(source);
            case "batchArena" -> MessageBatchOperations.batchArena(source, ArenaReuseFixtures.MESSAGE_BATCH_SIZE);
            case "threadLocalReuse" -> MessageBatchOperations.threadLocalReuse(source);
            case "globalPool" -> MessageBatchOperations.globalPool(source, messagePoolUnbounded);
            case "boundedPool" -> MessageBatchOperations.boundedPool(source, messagePoolBounded);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private long runTrees(long[] value, int docsPerBatch) {
        return switch (variant) {
            case "allocatePerItem" -> ParseTreeOperations.allocatePerItem(value, ArenaReuseFixtures.NODES_PER_DOC);
            case "batchArena" -> ParseTreeOperations.batchArena(value, ArenaReuseFixtures.NODES_PER_DOC, docsPerBatch);
            case "threadLocalReuse" -> ParseTreeOperations.threadLocalReuse(value, ArenaReuseFixtures.NODES_PER_DOC);
            case "globalPool" -> ParseTreeOperations.globalPool(value, ArenaReuseFixtures.NODES_PER_DOC, treePoolUnbounded);
            case "boundedPool" -> ParseTreeOperations.boundedPool(value, ArenaReuseFixtures.NODES_PER_DOC, treePoolBounded);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private long runBuffers(long[] word, int opsPerBatch) {
        return switch (variant) {
            case "allocatePerItem" -> ScratchBufferOperations.allocatePerItem(word, ArenaReuseFixtures.WORDS_PER_OP);
            case "batchArena" -> ScratchBufferOperations.batchArena(word, ArenaReuseFixtures.WORDS_PER_OP, opsPerBatch);
            case "threadLocalReuse" -> ScratchBufferOperations.threadLocalReuse(word, ArenaReuseFixtures.WORDS_PER_OP);
            case "globalPool" -> ScratchBufferOperations.globalPool(word, ArenaReuseFixtures.WORDS_PER_OP, bufferPoolUnbounded);
            case "boundedPool" -> ScratchBufferOperations.boundedPool(word, ArenaReuseFixtures.WORDS_PER_OP, bufferPoolBounded);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long fullPass() {
        return switch (dataset) {
            case "messageBatches" -> runMessages(messagesFull);
            case "temporaryParseTrees" -> runTrees(treesFull, ArenaReuseFixtures.DOCS_PER_BATCH);
            case "scratchBuffers" -> runBuffers(buffersFull, ArenaReuseFixtures.OPS_PER_BATCH);
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }

    /** One operation = one batch's worth of work — this lab's p99 metric, from JMH's own SampleTime percentiles. */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    public long oneBatchLatency() {
        return switch (dataset) {
            case "messageBatches" -> runMessages(messagesOneBatch);
            case "temporaryParseTrees" -> runTrees(treesOneBatch, ArenaReuseFixtures.DOCS_PER_BATCH);
            case "scratchBuffers" -> runBuffers(buffersOneBatch, ArenaReuseFixtures.OPS_PER_BATCH);
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }
}
