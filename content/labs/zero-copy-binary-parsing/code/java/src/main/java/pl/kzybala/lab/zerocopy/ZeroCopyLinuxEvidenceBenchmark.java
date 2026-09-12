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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

import static pl.kzybala.lab.zerocopy.ZeroCopyFixtures.WireBuffer;

/**
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 5×3
 * matrix. Wire-buffer encoding and the correctness oracle both run in
 * setup, never in the measured method.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class ZeroCopyLinuxEvidenceBenchmark {

    @Param({"copyingDecoder", "objectBuildingDecoder", "validatedZeroCopyView", "lazyFieldDecode", "mutableInPlaceUpdate"})
    public String variant;

    @Param({"fixedHeaderPlusVariablePayload", "nestedRepeatedFields", "utf8Field"})
    public String dataset;

    private WireBuffer wireBuffer;
    private int n;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }

        long expected;
        long actual;
        switch (dataset) {
            case "fixedHeaderPlusVariablePayload" -> {
                n = ZeroCopyFixtures.FIXED_HEADER_N;
                wireBuffer = ZeroCopyFixtures.encodeFixedHeader(n);
                expected = ZeroCopyFixtures.expectedFixedHeaderChecksum(wireBuffer, n);
                actual = runFixedHeader();
            }
            case "nestedRepeatedFields" -> {
                n = ZeroCopyFixtures.NESTED_N;
                wireBuffer = ZeroCopyFixtures.encodeNested(n);
                expected = ZeroCopyFixtures.expectedNestedChecksum(wireBuffer, n);
                actual = runNested();
            }
            case "utf8Field" -> {
                n = ZeroCopyFixtures.UTF8_N;
                wireBuffer = ZeroCopyFixtures.encodeUtf8(n);
                expected = ZeroCopyFixtures.expectedUtf8Checksum(wireBuffer, n);
                actual = runUtf8();
            }
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        }
        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (pin != null) pin.verifyAndRecord();
    }

    private long runFixedHeader() {
        return switch (variant) {
            case "copyingDecoder" -> FixedHeaderDecoders.copyingDecoder(wireBuffer, n);
            case "objectBuildingDecoder" -> FixedHeaderDecoders.objectBuildingDecoder(wireBuffer, n);
            case "validatedZeroCopyView" -> FixedHeaderDecoders.validatedZeroCopyView(wireBuffer, n);
            case "lazyFieldDecode" -> FixedHeaderDecoders.lazyFieldDecode(wireBuffer, n);
            case "mutableInPlaceUpdate" -> FixedHeaderDecoders.mutableInPlaceUpdate(wireBuffer, n);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private long runNested() {
        return switch (variant) {
            case "copyingDecoder" -> NestedRepeatedDecoders.copyingDecoder(wireBuffer, n);
            case "objectBuildingDecoder" -> NestedRepeatedDecoders.objectBuildingDecoder(wireBuffer, n);
            case "validatedZeroCopyView" -> NestedRepeatedDecoders.validatedZeroCopyView(wireBuffer, n);
            case "lazyFieldDecode" -> NestedRepeatedDecoders.lazyFieldDecode(wireBuffer, n);
            case "mutableInPlaceUpdate" -> NestedRepeatedDecoders.mutableInPlaceUpdate(wireBuffer, n);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    private long runUtf8() {
        return switch (variant) {
            case "copyingDecoder" -> Utf8FieldDecoders.copyingDecoder(wireBuffer, n);
            case "objectBuildingDecoder" -> Utf8FieldDecoders.objectBuildingDecoder(wireBuffer, n);
            case "validatedZeroCopyView" -> Utf8FieldDecoders.validatedZeroCopyView(wireBuffer, n);
            case "lazyFieldDecode" -> Utf8FieldDecoders.lazyFieldDecode(wireBuffer, n);
            case "mutableInPlaceUpdate" -> Utf8FieldDecoders.mutableInPlaceUpdate(wireBuffer, n);
            default -> throw new IllegalStateException("unknown variant: " + variant);
        };
    }

    /** One operation = one full pass over the declared dataset (setup excluded). */
    @Benchmark
    public long decodeAll() {
        return switch (dataset) {
            case "fixedHeaderPlusVariablePayload" -> runFixedHeader();
            case "nestedRepeatedFields" -> runNested();
            case "utf8Field" -> runUtf8();
            default -> throw new IllegalStateException("unknown dataset: " + dataset);
        };
    }
}
