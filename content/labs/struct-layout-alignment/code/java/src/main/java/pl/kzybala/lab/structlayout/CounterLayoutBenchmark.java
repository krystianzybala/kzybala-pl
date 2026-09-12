package pl.kzybala.lab.structlayout;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.Measurement;

import java.util.concurrent.TimeUnit;

/**
 * Companion dev/wiring benchmark for {@code producerConsumerCounters},
 * unpinned — for local smoke and IDE profiling. {@code @Group}/{@code
 * Scope.Group} is what makes JMH run each variant's two counter writes
 * concurrently on separate threads within one group — without it, this
 * would never reproduce the cross-core invalidation traffic being
 * measured, exactly matching content/labs/false-sharing's
 * {@code FalseSharingBenchmark}. Publication evidence comes exclusively
 * from {@link CounterLayoutLinuxEvidenceBenchmark} via the native-Linux
 * runner (see benchmark.md).
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CounterLayoutBenchmark {

    private final ProducerConsumerCounters.Natural natural = new ProducerConsumerCounters.Natural();
    private final ProducerConsumerCounters.PoorFieldOrder poor = new ProducerConsumerCounters.PoorFieldOrder();
    private final ProducerConsumerCounters.OptimizedFieldOrder optimized = new ProducerConsumerCounters.OptimizedFieldOrder();
    private final ProducerConsumerCounters.CacheLineAligned aligned = new ProducerConsumerCounters.CacheLineAligned();
    private final ProducerConsumerCounters.PackedUnaligned packed = new ProducerConsumerCounters.PackedUnaligned();

    @Benchmark
    @Group("natural")
    public void produce_natural() { natural.producerCount++; }

    @Benchmark
    @Group("natural")
    public void consume_natural() { natural.consumerCount++; }

    @Benchmark
    @Group("poorFieldOrder")
    public void produce_poor() { poor.producerCount++; }

    @Benchmark
    @Group("poorFieldOrder")
    public void consume_poor() { poor.consumerCount++; }

    @Benchmark
    @Group("optimizedFieldOrder")
    public void produce_optimized() { optimized.producerCount++; }

    @Benchmark
    @Group("optimizedFieldOrder")
    public void consume_optimized() { optimized.consumerCount++; }

    @Benchmark
    @Group("cacheLineAligned")
    public void produce_aligned() { aligned.producerCount++; }

    @Benchmark
    @Group("cacheLineAligned")
    public void consume_aligned() { aligned.consumerCount++; }

    @Benchmark
    @Group("packedUnaligned")
    public void produce_packed() { packed.producerCount++; }

    @Benchmark
    @Group("packedUnaligned")
    public void consume_packed() { packed.consumerCount++; }
}
