package pl.kzybala.lab.aosvssoa;

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
 * Companion dev/wiring benchmark for the "Array of Structures vs
 * Structure of Arrays" Performance Lab (kzybala.pl/lab/aos-vs-soa/): the
 * four layouts over the market-quotes dataset only, unpinned — for local
 * smoke and IDE profiling. Publication evidence comes exclusively from
 * {@link AosVsSoaLinuxEvidenceBenchmark} via the native-Linux runner (see
 * benchmark.md).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class AosVsSoaBenchmark {

    private static final int COLD_WORDS = 4; // market-quotes

    @Param({"aosHeap", "aosPacked", "soa", "hybrid"})
    public String variant;

    private AosHeapLayout heap;
    private AosPackedLayout packed;
    private SoaLayout soa;
    private HybridLayout hybrid;

    @Setup(Level.Trial)
    public void setup() {
        AosVsSoaFixtures.Generated data = AosVsSoaFixtures.generate(COLD_WORDS, AosVsSoaFixtures.SEED, AosVsSoaFixtures.N);
        switch (variant) {
            case "aosHeap" -> heap = AosHeapLayout.of(data);
            case "aosPacked" -> packed = AosPackedLayout.of(data, COLD_WORDS);
            case "soa" -> soa = SoaLayout.of(data, COLD_WORDS);
            case "hybrid" -> hybrid = HybridLayout.of(data, COLD_WORDS);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (packed != null) packed.close();
    }

    /** One operation = one full pass over 1,000,000 records, hot fields only. */
    @Benchmark
    public long sumHot() {
        return switch (variant) {
            case "aosHeap" -> heap.sumHot();
            case "aosPacked" -> packed.sumHot();
            case "soa" -> soa.sumHot();
            case "hybrid" -> hybrid.sumHot();
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
