package pl.kzybala.lab.structlayout;

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
 * Companion dev/wiring benchmark for the "Struct layout, alignment and
 * padding" Performance Lab (kzybala.pl/lab/struct-layout-alignment/):
 * the five layout variants over the {@code mixedPrimitiveRecord} dataset
 * only, unpinned — for local smoke and IDE profiling. Publication
 * evidence comes exclusively from {@link StructLayoutLinuxEvidenceBenchmark}
 * via the native-Linux runner (see benchmark.md). One operation = one
 * full sequential pass, summing every record's fields (setup excluded).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class StructLayoutBenchmark {

    @Param({"natural", "poorFieldOrder", "optimizedFieldOrder", "cacheLineAligned", "packedUnaligned"})
    public String variant;

    private MixedRecordLayout layout;

    @Setup(Level.Trial)
    public void setup() {
        StructLayoutFixtures.MixedSource source = StructLayoutFixtures.generateMixed(StructLayoutFixtures.MIXED_N);
        layout = switch (variant) {
            case "natural" -> MixedRecordLayout.natural(source);
            case "poorFieldOrder" -> MixedRecordLayout.poorFieldOrder(source);
            case "optimizedFieldOrder" -> MixedRecordLayout.optimizedFieldOrder(source);
            case "cacheLineAligned" -> MixedRecordLayout.cacheLineAligned(source);
            case "packedUnaligned" -> MixedRecordLayout.packedUnaligned(source);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        layout.close();
    }

    @Benchmark
    public long sequentialSum() {
        return layout.sum();
    }
}
