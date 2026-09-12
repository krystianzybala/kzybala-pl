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
 * Publication-evidence benchmark for the native-Linux runner: one pinned
 * worker executing exactly one (variant, dataset) cell of the 5×2
 * matrix (the sequential-access datasets — {@code producerConsumerCounters}
 * has its own class, {@link CounterLayoutLinuxEvidenceBenchmark}, since
 * it measures concurrent throughput, not sequential ns/record). Dataset
 * generation and the correctness oracle both run in setup, never in the
 * measured method.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class StructLayoutLinuxEvidenceBenchmark {

    @Param({"natural", "poorFieldOrder", "optimizedFieldOrder", "cacheLineAligned", "packedUnaligned"})
    public String variant;

    @Param({"mixedPrimitiveRecord", "headerPlusPayload"})
    public String dataset;

    private MixedRecordLayout mixed;
    private HeaderPayloadLayout header;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setup() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }

        long expected;
        long actual;
        if ("mixedPrimitiveRecord".equals(dataset)) {
            StructLayoutFixtures.MixedSource source = StructLayoutFixtures.generateMixed(StructLayoutFixtures.MIXED_N);
            expected = StructLayoutFixtures.expectedMixedChecksum(source);
            mixed = switch (variant) {
                case "natural" -> MixedRecordLayout.natural(source);
                case "poorFieldOrder" -> MixedRecordLayout.poorFieldOrder(source);
                case "optimizedFieldOrder" -> MixedRecordLayout.optimizedFieldOrder(source);
                case "cacheLineAligned" -> MixedRecordLayout.cacheLineAligned(source);
                case "packedUnaligned" -> MixedRecordLayout.packedUnaligned(source);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            actual = mixed.sum();
        } else if ("headerPlusPayload".equals(dataset)) {
            StructLayoutFixtures.HeaderSource source = StructLayoutFixtures.generateHeader(StructLayoutFixtures.HEADER_N);
            expected = StructLayoutFixtures.expectedHeaderChecksum(source);
            header = switch (variant) {
                case "natural" -> HeaderPayloadLayout.natural(source);
                case "poorFieldOrder" -> HeaderPayloadLayout.poorFieldOrder(source);
                case "optimizedFieldOrder" -> HeaderPayloadLayout.optimizedFieldOrder(source);
                case "cacheLineAligned" -> HeaderPayloadLayout.cacheLineAligned(source);
                case "packedUnaligned" -> HeaderPayloadLayout.packedUnaligned(source);
                default -> throw new IllegalStateException("unknown variant: " + variant);
            };
            actual = header.sum();
        } else {
            throw new IllegalStateException("unknown dataset: " + dataset);
        }

        if (actual != expected) {
            throw new IllegalStateException(
                "correctness oracle failed for variant=" + variant + " dataset=" + dataset
                    + ": expected " + expected + " got " + actual);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (mixed != null) mixed.close();
        if (header != null) header.close();
        if (pin != null) pin.verifyAndRecord();
    }

    @Benchmark
    public long sequentialSum() {
        return mixed != null ? mixed.sum() : header.sum();
    }
}
