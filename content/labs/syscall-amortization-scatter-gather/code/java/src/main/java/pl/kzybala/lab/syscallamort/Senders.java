package pl.kzybala.lab.syscallamort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * The five sender variants, all writing the same {@link MessageFormat}
 * wire format to a connected {@link SocketChannel}. Every variant
 * correctly handles partial writes (looping until every buffer is fully
 * drained) — this lab never demonstrates the "ignoring partial writes"
 * trap in its own reference implementations, only in {@code exercises.md}.
 */
public final class Senders {

    private Senders() {
    }

    /** Variant: single small write — two separate write() calls per message (header, then payload). */
    public static WriteAccounting singleSmallWrite(SocketChannel channel, long messageCount, int payloadSize)
            throws IOException {
        long writeCalls = 0, bytesWritten = 0, partialWrites = 0;
        for (long seq = 0; seq < messageCount; seq++) {
            ByteBuffer header = ByteBuffer.wrap(MessageFormat.buildHeader(seq, payloadSize));
            ByteBuffer payload = ByteBuffer.wrap(MessageFormat.buildPayload(seq, payloadSize));
            for (ByteBuffer buf : new ByteBuffer[]{header, payload}) {
                while (buf.hasRemaining()) {
                    int before = buf.remaining();
                    int n = channel.write(buf);
                    writeCalls++;
                    bytesWritten += n;
                    if (n < before) {
                        partialWrites++;
                    }
                }
            }
        }
        return new WriteAccounting(writeCalls, bytesWritten, partialWrites, messageCount);
    }

    /** Variant: coalesced buffer — header and payload copied into ONE buffer, one write() call per message. */
    public static WriteAccounting coalescedBuffer(SocketChannel channel, long messageCount, int payloadSize)
            throws IOException {
        long writeCalls = 0, bytesWritten = 0, partialWrites = 0;
        for (long seq = 0; seq < messageCount; seq++) {
            byte[] header = MessageFormat.buildHeader(seq, payloadSize);
            byte[] payload = MessageFormat.buildPayload(seq, payloadSize);
            byte[] combined = new byte[header.length + payload.length]; // the extra copy this variant pays for
            System.arraycopy(header, 0, combined, 0, header.length);
            System.arraycopy(payload, 0, combined, header.length, payload.length);
            ByteBuffer buf = ByteBuffer.wrap(combined);
            while (buf.hasRemaining()) {
                int before = buf.remaining();
                int n = channel.write(buf);
                writeCalls++;
                bytesWritten += n;
                if (n < before) {
                    partialWrites++;
                }
            }
        }
        return new WriteAccounting(writeCalls, bytesWritten, partialWrites, messageCount);
    }

    /** Variant: scatter/gather write — header and payload written from TWO separate buffers in one gathering write() call. */
    public static WriteAccounting scatterGatherWrite(SocketChannel channel, long messageCount, int payloadSize)
            throws IOException {
        long writeCalls = 0, bytesWritten = 0, partialWrites = 0;
        for (long seq = 0; seq < messageCount; seq++) {
            ByteBuffer[] buffers = {
                    ByteBuffer.wrap(MessageFormat.buildHeader(seq, payloadSize)),
                    ByteBuffer.wrap(MessageFormat.buildPayload(seq, payloadSize)),
            };
            long total = remaining(buffers);
            while (remaining(buffers) > 0) {
                long before = remaining(buffers);
                long n = channel.write(buffers);
                writeCalls++;
                bytesWritten += n;
                if (n < before) {
                    partialWrites++;
                }
            }
            assert total == MessageFormat.HEADER_SIZE + payloadSize;
        }
        return new WriteAccounting(writeCalls, bytesWritten, partialWrites, messageCount);
    }

    /**
     * Variant: size/time-bounded batch — accumulates messages' header+payload
     * buffers until {@code batchSize} messages are ready, then issues ONE
     * gathering write covering the entire batch (amortizing the per-message
     * write-call cost below 1/message).
     */
    public static WriteAccounting sizeBoundedBatch(SocketChannel channel, long messageCount, int payloadSize, int batchSize)
            throws IOException {
        long writeCalls = 0, bytesWritten = 0, partialWrites = 0;
        long seq = 0;
        while (seq < messageCount) {
            int n = (int) Math.min(batchSize, messageCount - seq);
            List<ByteBuffer> batch = new ArrayList<>(n * 2);
            for (int i = 0; i < n; i++) {
                batch.add(ByteBuffer.wrap(MessageFormat.buildHeader(seq + i, payloadSize)));
                batch.add(ByteBuffer.wrap(MessageFormat.buildPayload(seq + i, payloadSize)));
            }
            ByteBuffer[] buffers = batch.toArray(new ByteBuffer[0]);
            while (remaining(buffers) > 0) {
                long before = remaining(buffers);
                long written = channel.write(buffers);
                writeCalls++;
                bytesWritten += written;
                if (written < before) {
                    partialWrites++;
                }
            }
            seq += n;
        }
        return new WriteAccounting(writeCalls, bytesWritten, partialWrites, messageCount);
    }

    private static long remaining(ByteBuffer[] buffers) {
        long total = 0;
        for (ByteBuffer b : buffers) {
            total += b.remaining();
        }
        return total;
    }
}
