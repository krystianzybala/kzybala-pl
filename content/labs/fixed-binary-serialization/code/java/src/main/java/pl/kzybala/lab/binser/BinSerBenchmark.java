package pl.kzybala.lab.binser;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing encode/decode cost across the three Java codec
 * variants, over the four dataset profiles. {@code @Param} drives the
 * profile axis so each JMH trial name records which dataset it measured,
 * matching the "one scenario per process invocation, never mixed" contract.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
public class BinSerBenchmark {

    @Param({"smallCommand", "mediumEvent", "repeatedFields", "versionedOptionalField"})
    public String profile;

    private EventRecord event;
    private byte[] genericWire;
    private byte[] byteBufferWire;
    private byte[] ffmWire;

    private final GenericObjectCodec genericCodec = new GenericObjectCodec();
    private final ByteBufferCodec byteBufferCodec = new ByteBufferCodec();
    private final FfmFlyweightCodec ffmCodec = new FfmFlyweightCodec();

    @Setup
    public void setUp() {
        event = switch (profile) {
            case "smallCommand" -> BinSerFixtures.smallCommand();
            case "mediumEvent" -> BinSerFixtures.mediumEvent();
            case "repeatedFields" -> BinSerFixtures.repeatedFields();
            case "versionedOptionalField" -> BinSerFixtures.versionedOptionalField();
            default -> throw new IllegalStateException("unknown profile: " + profile);
        };
        genericWire = genericCodec.encode(event);
        byteBufferWire = byteBufferCodec.encode(event);
        ffmWire = ffmCodec.encode(event);
    }

    @Benchmark
    public void genericEncode(Blackhole bh) {
        bh.consume(genericCodec.encode(event));
    }

    @Benchmark
    public void genericDecode(Blackhole bh) {
        bh.consume(genericCodec.decode(genericWire));
    }

    @Benchmark
    public void byteBufferEncode(Blackhole bh) {
        bh.consume(byteBufferCodec.encode(event));
    }

    @Benchmark
    public void byteBufferDecode(Blackhole bh) {
        bh.consume(byteBufferCodec.decode(byteBufferWire));
    }

    @Benchmark
    public void ffmEncode(Blackhole bh) {
        bh.consume(ffmCodec.encode(event));
    }

    @Benchmark
    public void ffmDecode(Blackhole bh) {
        bh.consume(ffmCodec.decode(ffmWire));
    }

    @Benchmark
    public void ffmDecodeFieldOnly(Blackhole bh) {
        // Decodes only one field via the flyweight view, without materializing
        // the full EventRecord — the specific cost ByteBuffer/generic can't avoid.
        bh.consume(ffmCodec.decode(ffmWire).deviceId());
    }
}
