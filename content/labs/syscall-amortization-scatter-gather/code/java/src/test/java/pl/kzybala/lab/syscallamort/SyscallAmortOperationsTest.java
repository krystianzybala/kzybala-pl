package pl.kzybala.lab.syscallamort;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyscallAmortOperationsTest {

    @Test
    void messageFormatRoundTripsSequenceAndPayload() {
        for (int size : new int[]{8, 64, 256, 1024}) {
            for (long seq : new long[]{0, 1, 255, 256, 100_000}) {
                byte[] header = MessageFormat.buildHeader(seq, size);
                assertEquals(seq, MessageFormat.readLong(header, 0));
                assertEquals(size, MessageFormat.readLong(header, 8));
                byte[] payload = MessageFormat.buildPayload(seq, size);
                assertTrue(MessageFormat.payloadMatches(payload, seq));
            }
        }
    }

    @Test
    void singleSmallWriteDeliversEveryMessage() throws Exception {
        long messageCount = 2000;
        int payloadSize = 256;
        var executor = Executors.newSingleThreadExecutor();
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            CompletableFuture<Long> deliveredFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Receiver.receive(conn.server, messageCount);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            WriteAccounting accounting = Senders.singleSmallWrite(conn.client, messageCount, payloadSize);
            long delivered = deliveredFuture.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, delivered);
            assertEquals(messageCount * 2, accounting.writeCalls(), "two write() calls per message");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void coalescedBufferDeliversEveryMessageWithOneWriteCallPerMessage() throws Exception {
        long messageCount = 2000;
        int payloadSize = 256;
        var executor = Executors.newSingleThreadExecutor();
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            CompletableFuture<Long> deliveredFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Receiver.receive(conn.server, messageCount);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            WriteAccounting accounting = Senders.coalescedBuffer(conn.client, messageCount, payloadSize);
            long delivered = deliveredFuture.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, delivered);
            assertEquals(messageCount, accounting.writeCalls(), "one write() call per message, no partial writes expected on loopback at this size");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void scatterGatherWriteDeliversEveryMessageWithOneWriteCallPerMessage() throws Exception {
        long messageCount = 2000;
        int payloadSize = 256;
        var executor = Executors.newSingleThreadExecutor();
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            CompletableFuture<Long> deliveredFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Receiver.receive(conn.server, messageCount);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            WriteAccounting accounting = Senders.scatterGatherWrite(conn.client, messageCount, payloadSize);
            long delivered = deliveredFuture.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, delivered);
            assertEquals(messageCount, accounting.writeCalls(), "one gathering write() call per message expected on loopback at this size");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void sizeBoundedBatchDeliversEveryMessageWithFarFewerWriteCallsThanMessages() throws Exception {
        long messageCount = 2000;
        int payloadSize = 64;
        int batchSize = 20;
        var executor = Executors.newSingleThreadExecutor();
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            CompletableFuture<Long> deliveredFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Receiver.receive(conn.server, messageCount);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            WriteAccounting accounting = Senders.sizeBoundedBatch(conn.client, messageCount, payloadSize, batchSize);
            long delivered = deliveredFuture.get(10, TimeUnit.SECONDS);
            assertEquals(messageCount, delivered);
            assertTrue(accounting.writeCalls() <= messageCount / batchSize + 5,
                    "batching should reduce write calls to roughly messageCount/batchSize, was " + accounting.writeCalls());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void backpressuredReceiverStillDeliversEveryMessageDespiteInducedPartialWrites() throws Exception {
        long messageCount = 300;
        int payloadSize = 4096; // large enough, with a slow receiver, to fill the socket send buffer
        var executor = Executors.newSingleThreadExecutor();
        try (LoopbackConnection conn = LoopbackConnection.open()) {
            conn.client.socket().setSendBufferSize(8192);
            conn.server.socket().setReceiveBufferSize(8192);
            CompletableFuture<Long> deliveredFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return Receiver.receiveSlowly(conn.server, messageCount, 2);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executor);
            WriteAccounting accounting = Senders.scatterGatherWrite(conn.client, messageCount, payloadSize);
            long delivered = deliveredFuture.get(30, TimeUnit.SECONDS);
            assertEquals(messageCount, delivered);
            // The point of this scenario: partial writes are expected and handled
            // correctly, not avoided — every byte still arrives correctly either way.
            assertEquals(accounting.bytesWritten(), (long) messageCount * (MessageFormat.HEADER_SIZE + payloadSize));
        } finally {
            executor.shutdown();
        }
    }
}
