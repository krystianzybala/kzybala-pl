package pl.kzybala.lab.udpingest;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the four measurable variants (batched Linux
 * receive is capability-unavailable on this repository's toolchain — see
 * theory.md) over the three datagram-size profiles, all over UDP
 * loopback (127.0.0.1) — explicitly labeled everywhere, never presented
 * as real-network evidence. Each benchmark invocation spawns a sender on
 * a background thread and measures the receiver's full run, matching
 * this lab's "one benchmark operation = one full scenario pass" contract.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 2, time = 1)
public class UdpIngestBenchmark {

    @Param({"small64Byte", "medium256Byte", "large1400Byte"})
    public String profile;

    private static final long MESSAGE_COUNT = 4_000;
    private static final int QUEUE_CAPACITY = 4096;

    private int datagramSize;
    private ExecutorService senderExecutor;

    @Setup(Level.Trial)
    public void setUp() {
        datagramSize = switch (profile) {
            case "small64Byte" -> 64;
            case "medium256Byte" -> 256;
            case "large1400Byte" -> 1400;
            default -> throw new IllegalStateException("unknown profile: " + profile);
        };
        senderExecutor = Executors.newSingleThreadExecutor();
    }

    @org.openjdk.jmh.annotations.TearDown(Level.Trial)
    public void tearDown() {
        // Without an explicit shutdown, this non-daemon single-thread pool
        // keeps the forked JVM alive after JMH's own benchmark loop
        // finishes ("did not exit, are there stray running threads?").
        senderExecutor.shutdown();
    }

    private static int freePort() throws Exception {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void submitSender(int port, long messageCount, int size) {
        senderExecutor.submit(() -> {
            try {
                new LoadGenerator(port).sendBurst(messageCount, size);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Benchmark
    public void onePacketPerReceive(Blackhole bh) throws Exception {
        int port = freePort();
        try (RawReceiveKernel kernel = new RawReceiveKernel(port)) {
            submitSender(port, MESSAGE_COUNT, datagramSize);
            bh.consume(kernel.receiveAllocatingPerPacket(MESSAGE_COUNT, datagramSize));
        }
    }

    @Benchmark
    public void reusedDirectBuffer(Blackhole bh) throws Exception {
        int port = freePort();
        try (RawReceiveKernel kernel = new RawReceiveKernel(port)) {
            submitSender(port, MESSAGE_COUNT, datagramSize);
            bh.consume(kernel.receiveWithReusedBuffer(MESSAGE_COUNT, datagramSize));
        }
    }

    @Benchmark
    public void copyingHandoff(Blackhole bh) throws Exception {
        int port = freePort();
        try (CopyingHandoffPipeline pipeline = new CopyingHandoffPipeline(port, QUEUE_CAPACITY)) {
            submitSender(port, MESSAGE_COUNT, datagramSize);
            bh.consume(pipeline.run(MESSAGE_COUNT, datagramSize));
        }
    }

    @Benchmark
    public void zeroCopyViewHandoff(Blackhole bh) throws Exception {
        int port = freePort();
        try (ZeroCopyHandoffPipeline pipeline = new ZeroCopyHandoffPipeline(port, QUEUE_CAPACITY)) {
            submitSender(port, MESSAGE_COUNT, datagramSize);
            bh.consume(pipeline.run(MESSAGE_COUNT, datagramSize));
        }
    }
}
