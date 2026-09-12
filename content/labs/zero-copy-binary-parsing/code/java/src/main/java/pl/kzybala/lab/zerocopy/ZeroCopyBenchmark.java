package pl.kzybala.lab.zerocopy;

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

import static pl.kzybala.lab.zerocopy.ZeroCopyFixtures.WireBuffer;

/**
 * Companion dev/wiring benchmark for the "Zero-copy binary parsing and
 * views" Performance Lab (kzybala.pl/lab/zero-copy-binary-parsing/): the
 * five decoder variants over the {@code fixedHeaderPlusVariablePayload}
 * dataset only, unpinned — for local smoke and IDE profiling.
 * Publication evidence comes exclusively from
 * {@link ZeroCopyLinuxEvidenceBenchmark} via the native-Linux runner
 * (see benchmark.md). One operation = one full pass over the declared
 * dataset (setup — encoding the wire buffer — excluded).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ZeroCopyBenchmark {

    @Param({"copyingDecoder", "objectBuildingDecoder", "validatedZeroCopyView", "lazyFieldDecode", "mutableInPlaceUpdate"})
    public String variant;

    private WireBuffer wireBuffer;

    @Setup(Level.Trial)
    public void setup() {
        wireBuffer = ZeroCopyFixtures.encodeFixedHeader(ZeroCopyFixtures.FIXED_HEADER_N);
    }

    @Benchmark
    public long decodeAll() {
        int n = ZeroCopyFixtures.FIXED_HEADER_N;
        return switch (variant) {
            case "copyingDecoder" -> FixedHeaderDecoders.copyingDecoder(wireBuffer, n);
            case "objectBuildingDecoder" -> FixedHeaderDecoders.objectBuildingDecoder(wireBuffer, n);
            case "validatedZeroCopyView" -> FixedHeaderDecoders.validatedZeroCopyView(wireBuffer, n);
            case "lazyFieldDecode" -> FixedHeaderDecoders.lazyFieldDecode(wireBuffer, n);
            case "mutableInPlaceUpdate" -> FixedHeaderDecoders.mutableInPlaceUpdate(wireBuffer, n);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }
}
