package pl.kzybala.lab.shmipc;

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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark comparing the socket baseline against the shared-memory
 * variants (copy, zero-copy slot view, batched) over the three payload
 * profiles. The shared-memory variants here use two independent mappings
 * of the same backing file within one JVM (see java.md for why this is a
 * closer analogue of real cross-process sharing than one shared Java
 * object, while the {@code ProcessLauncherTest} correctness suite is the
 * lab's actual cross-process proof, not this throughput microbenchmark).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
public class ShmIpcBenchmark {

    @Param({"small32Byte", "medium128Byte", "large1KiB"})
    public String profile;

    private static final long MESSAGE_COUNT = 2_000;

    private int payloadSize;
    private Path segmentPath;
    private SharedRing writerView;
    private SharedRing readerView;
    private final SocketBaselineKernel socketKernel = new SocketBaselineKernel();

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        payloadSize = switch (profile) {
            case "small32Byte" -> 32;
            case "medium128Byte" -> 128;
            case "large1KiB" -> 1000;
            default -> throw new IllegalStateException("unknown profile: " + profile);
        };
        segmentPath = Files.createTempFile("shmipc-lab-", ".bin");
        writerView = SharedRing.createNew(segmentPath, 256);
        writerView.initWriter();
        readerView = SharedRing.openExisting(segmentPath);
        readerView.initReader();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        socketKernel.shutdown();
        Files.deleteIfExists(segmentPath);
    }

    @Benchmark
    public void socketBaseline(Blackhole bh) throws Exception {
        bh.consume(socketKernel.runOnce(MESSAGE_COUNT, payloadSize));
    }

    @Benchmark
    public void sharedMemoryCopyPayload(Blackhole bh) {
        long checksum = 0;
        byte[] out = new byte[SharedRing.PAYLOAD_CAPACITY];
        for (long i = 0; i < MESSAGE_COUNT; i++) {
            byte[] payload = MessageFixtures.payload(i, payloadSize);
            while (!writerView.tryPublish(payload, payloadSize)) {
                Thread.onSpinWait();
            }
            int length = -1;
            while (length < 0) {
                length = readerView.tryConsume(out);
            }
            checksum += MessageFixtures.checksum(i, out, length);
        }
        bh.consume(checksum);
    }

    @Benchmark
    public void sharedMemorySlotView(Blackhole bh) {
        long checksum = 0;
        for (long i = 0; i < MESSAGE_COUNT; i++) {
            byte[] payload = MessageFixtures.payload(i, payloadSize);
            while (!writerView.tryPublish(payload, payloadSize)) {
                Thread.onSpinWait();
            }
            SharedRing.SlotView view = null;
            while (view == null) {
                view = readerView.tryConsumeView();
            }
            long h = i * 31;
            for (int b = 0; b < view.length(); b++) {
                h = h * 31 + view.byteAt(b);
            }
            checksum += h;
        }
        bh.consume(checksum);
    }

    @Benchmark
    public void sharedMemoryBatched(Blackhole bh) {
        int batchSize = 8;
        byte[][] payloads = new byte[batchSize][];
        int[] lengths = new int[batchSize];
        byte[] out = new byte[SharedRing.PAYLOAD_CAPACITY];
        long checksum = 0;
        for (long base = 0; base < MESSAGE_COUNT; base += batchSize) {
            int n = (int) Math.min(batchSize, MESSAGE_COUNT - base);
            for (int j = 0; j < n; j++) {
                payloads[j] = MessageFixtures.payload(base + j, payloadSize);
                lengths[j] = payloadSize;
            }
            byte[][] toPublish = n == batchSize ? payloads : java.util.Arrays.copyOf(payloads, n);
            int[] toPublishLengths = n == batchSize ? lengths : java.util.Arrays.copyOf(lengths, n);
            int published = 0;
            while (published < n) {
                published += writerView.tryPublishBatch(
                        java.util.Arrays.copyOfRange(toPublish, published, n),
                        java.util.Arrays.copyOfRange(toPublishLengths, published, n));
            }
            for (int j = 0; j < n; j++) {
                int length = -1;
                while (length < 0) {
                    length = readerView.tryConsume(out);
                }
                checksum += MessageFixtures.checksum(base + j, out, length);
            }
        }
        bh.consume(checksum);
    }
}
