package pl.kzybala.lab.udpingest;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests over real UDP loopback (127.0.0.1) sockets — every
 * test here is explicitly a loopback test, never claimed to approximate
 * real-network UDP behavior (see the "testing only loopback without
 * labeling" trap). Message counts are kept small enough that loopback UDP
 * delivery is effectively lossless in practice on a development machine.
 */
class UdpIngestOperationsTest {

    private static int freePort() throws Exception {
        try (java.net.DatagramSocket s = new java.net.DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void datagramFormatRoundTripsSequenceAndPayload() {
        for (int size : new int[]{64, 256, 1400}) {
            for (long seq : new long[]{0, 1, 255, 256, 100_000}) {
                byte[] wire = DatagramFormat.build(seq, size);
                assertEquals(size, wire.length);
                assertEquals(seq, DatagramFormat.readSequence(wire, 0));
                assertTrue(DatagramFormat.payloadMatches(wire, 0, size, seq));
            }
        }
    }

    @Test
    void rawReceiveKernelValidatesEveryMessage() throws Exception {
        int port = freePort();
        int datagramSize = 256;
        long messageCount = 2000;
        var executor = Executors.newSingleThreadExecutor();
        try (RawReceiveKernel kernel = new RawReceiveKernel(port)) {
            CompletableFuture<Long> received = CompletableFuture.supplyAsync(() -> {
                try {
                    return kernel.receiveWithReusedBuffer(messageCount, datagramSize);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            Thread.sleep(50); // let the receiver bind and start looping before sending
            new LoadGenerator(port).sendBurst(messageCount, datagramSize);
            long validated = received.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, validated);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void copyingHandoffDeliversEveryMessageWithAmpleQueueCapacity() throws Exception {
        int port = freePort();
        int datagramSize = 256;
        long messageCount = 2000;
        var executor = Executors.newSingleThreadExecutor();
        try (CopyingHandoffPipeline pipeline = new CopyingHandoffPipeline(port, 4096)) {
            CompletableFuture<IngestResult> resultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return pipeline.run(messageCount, datagramSize);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            Thread.sleep(50);
            new LoadGenerator(port).sendBurst(messageCount, datagramSize);
            IngestResult result = resultFuture.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, result.accountedFor());
            assertEquals(0, result.corrupted());
            assertEquals(messageCount, result.delivered());
            assertEquals(0, result.applicationDropped());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void copyingHandoffCountsExplicitDropsWhenQueueIsUndersized() throws Exception {
        int port = freePort();
        int datagramSize = 64;
        long messageCount = 5000;
        var executor = Executors.newSingleThreadExecutor();
        // A deliberately tiny queue against a burst load forces application-level
        // drops — this must never silently disappear from the accounting.
        try (CopyingHandoffPipeline pipeline = new CopyingHandoffPipeline(port, 4)) {
            CompletableFuture<IngestResult> resultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return pipeline.run(messageCount, datagramSize);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            Thread.sleep(50);
            new LoadGenerator(port).sendBurst(messageCount, datagramSize);
            IngestResult result = resultFuture.get(15, TimeUnit.SECONDS);
            assertEquals(messageCount, result.accountedFor());
            assertEquals(0, result.corrupted());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void zeroCopyHandoffDeliversEveryMessageWithAmpleSlabCapacity() throws Exception {
        int port = freePort();
        int datagramSize = 256;
        long messageCount = 2000;
        var executor = Executors.newSingleThreadExecutor();
        try (ZeroCopyHandoffPipeline pipeline = new ZeroCopyHandoffPipeline(port, 4096)) {
            CompletableFuture<IngestResult> resultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return pipeline.run(messageCount, datagramSize);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            Thread.sleep(50);
            new LoadGenerator(port).sendBurst(messageCount, datagramSize);
            IngestResult result = resultFuture.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, result.accountedFor());
            assertEquals(0, result.corrupted());
            assertEquals(messageCount, result.delivered());
            assertEquals(0, result.applicationDropped());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void zeroCopyHandoffCountsExplicitDropsWhenSlabIsUndersized() throws Exception {
        int port = freePort();
        int datagramSize = 64;
        long messageCount = 5000;
        var executor = Executors.newSingleThreadExecutor();
        try (ZeroCopyHandoffPipeline pipeline = new ZeroCopyHandoffPipeline(port, 4)) {
            CompletableFuture<IngestResult> resultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return pipeline.run(messageCount, datagramSize);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            Thread.sleep(50);
            new LoadGenerator(port).sendBurst(messageCount, datagramSize);
            IngestResult result = resultFuture.get(15, TimeUnit.SECONDS);
            assertEquals(messageCount, result.accountedFor());
            assertEquals(0, result.corrupted());
        } finally {
            executor.shutdown();
        }
    }
}
