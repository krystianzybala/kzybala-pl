package pl.kzybala.lab.structlayout;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
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
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for {@code producerConsumerCounters} on
 * the native-Linux runner — the direct structural counterpart of
 * content/labs/false-sharing's {@code FalseSharingLinuxEvidenceBenchmark},
 * extended to this lab's five layout variants via a {@code variant}
 * parameter so exactly one is measured per JVM invocation.
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CounterLayoutLinuxEvidenceBenchmark {

    @Param({"natural", "poorFieldOrder", "optimizedFieldOrder", "cacheLineAligned", "packedUnaligned"})
    public String variant;

    private ProducerConsumerCounters.Natural natural;
    private ProducerConsumerCounters.PoorFieldOrder poor;
    private ProducerConsumerCounters.OptimizedFieldOrder optimized;
    private ProducerConsumerCounters.CacheLineAligned aligned;
    private ProducerConsumerCounters.PackedUnaligned packed;

    @State(Scope.Thread)
    public static class PinProducer {
        WorkerPin pin;

        @Setup(Level.Trial)
        public void pin() {
            if (WorkerPin.pinningRequested()) {
                pin = WorkerPin.establish("produce", WorkerPin.CPU_A);
            }
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @State(Scope.Thread)
    public static class PinConsumer {
        WorkerPin pin;

        @Setup(Level.Trial)
        public void pin() {
            if (WorkerPin.pinningRequested()) {
                if (WorkerPin.CPU_B == null) {
                    throw new IllegalStateException("plab.cpuA is set but plab.cpuB is not — both writers must be pinned or neither");
                }
                pin = WorkerPin.establish("consume", WorkerPin.CPU_B);
            }
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @Setup(Level.Trial)
    public void setUp(ThreadParams threads) {
        if (threads.getGroupThreadCount() != 2) {
            throw new IllegalStateException(
                "struct-layout-alignment counter evidence requires exactly 2 group threads, got "
                    + threads.getGroupThreadCount() + " — run with -t 2");
        }
        natural = new ProducerConsumerCounters.Natural();
        poor = new ProducerConsumerCounters.PoorFieldOrder();
        optimized = new ProducerConsumerCounters.OptimizedFieldOrder();
        aligned = new ProducerConsumerCounters.CacheLineAligned();
        packed = new ProducerConsumerCounters.PackedUnaligned();
    }

    @Benchmark
    @Group("counters")
    public void produce(PinProducer pinned) {
        switch (variant) {
            case "natural" -> natural.producerCount++;
            case "poorFieldOrder" -> poor.producerCount++;
            case "optimizedFieldOrder" -> optimized.producerCount++;
            case "cacheLineAligned" -> aligned.producerCount++;
            case "packedUnaligned" -> packed.producerCount++;
            default -> throw new IllegalStateException("unknown variant: " + variant);
        }
    }

    @Benchmark
    @Group("counters")
    public void consume(PinConsumer pinned) {
        switch (variant) {
            case "natural" -> natural.consumerCount++;
            case "poorFieldOrder" -> poor.consumerCount++;
            case "optimizedFieldOrder" -> optimized.consumerCount++;
            case "cacheLineAligned" -> aligned.consumerCount++;
            case "packedUnaligned" -> packed.consumerCount++;
            default -> throw new IllegalStateException("unknown variant: " + variant);
        }
    }

    @TearDown(Level.Trial)
    public void validate() {
        long p = switch (variant) {
            case "natural" -> natural.producerCount;
            case "poorFieldOrder" -> poor.producerCount;
            case "optimizedFieldOrder" -> optimized.producerCount;
            case "cacheLineAligned" -> aligned.producerCount;
            case "packedUnaligned" -> packed.producerCount;
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        long c = switch (variant) {
            case "natural" -> natural.consumerCount;
            case "poorFieldOrder" -> poor.consumerCount;
            case "optimizedFieldOrder" -> optimized.consumerCount;
            case "cacheLineAligned" -> aligned.consumerCount;
            case "packedUnaligned" -> packed.consumerCount;
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
        if (p <= 0 || c <= 0) {
            throw new IllegalStateException(
                "counter did not advance (producer=" + p + ", consumer=" + c
                    + ") — a writer thread was starved or the write was eliminated; run is invalid");
        }
    }
}
