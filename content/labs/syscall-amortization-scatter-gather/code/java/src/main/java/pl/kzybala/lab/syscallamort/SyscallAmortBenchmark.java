package pl.kzybala.lab.syscallamort;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the four throughput-measurable variants
 * (backpressured receiver is a correctness/resilience scenario, not a
 * throughput comparison — see benchmark.md) over the three dataset
 * profiles, all over TCP loopback (127.0.0.1). Each invocation opens a
 * fresh loopback connection and spawns the receiver on a background
 * thread, matching this lab's "one benchmark operation = one full
 * scenario pass" contract.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class SyscallAmortBenchmark {

    @Param({"headerPlusPayload", "smallMessages", "mixedSizes"})
    public String profile;

    private long messageCount;
    private int payloadSize;
    private ExecutorService receiverExecutor;

    @Setup(Level.Trial)
    public void setUp() {
        switch (profile) {
            case "headerPlusPayload" -> {
                messageCount = 2000;
                payloadSize = 256;
            }
            case "smallMessages" -> {
                messageCount = 5000;
                payloadSize = 8;
            }
            case "mixedSizes" -> {
                // Fixed representative size for this benchmark's timed region;
                // the full mixed-size sweep is exercised by the correctness suite.
                messageCount = 2000;
                payloadSize = 256;
            }
            default -> throw new IllegalStateException("unknown profile: " + profile);
        }
        receiverExecutor = Executors.newSingleThreadExecutor();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        receiverExecutor.shutdown();
    }

    private void submitReceiver(java.nio.channels.SocketChannel server, long count) {
        receiverExecutor.submit(() -> {
            try {
                Receiver.receive(server, count);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Benchmark
    public void singleSmallWrite(Blackhole bh) throws Exception {
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            submitReceiver(conn.server, messageCount);
            bh.consume(Senders.singleSmallWrite(conn.client, messageCount, payloadSize));
        }
    }

    @Benchmark
    public void coalescedBuffer(Blackhole bh) throws Exception {
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            submitReceiver(conn.server, messageCount);
            bh.consume(Senders.coalescedBuffer(conn.client, messageCount, payloadSize));
        }
    }

    @Benchmark
    public void scatterGatherWrite(Blackhole bh) throws Exception {
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            submitReceiver(conn.server, messageCount);
            bh.consume(Senders.scatterGatherWrite(conn.client, messageCount, payloadSize));
        }
    }

    @Benchmark
    public void sizeBoundedBatch(Blackhole bh) throws Exception {
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            submitReceiver(conn.server, messageCount);
            bh.consume(Senders.sizeBoundedBatch(conn.client, messageCount, payloadSize, 20));
        }
    }
}
