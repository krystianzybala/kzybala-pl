package pl.kzybala.lab.syscallamort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Reads {@code messageCount} messages off a connected {@link SocketChannel}
 * and validates each against {@link MessageFormat}. {@link #receiveSlowly}
 * inserts a deliberate per-message delay — used only by the
 * "backpressured receiver" variant to induce real TCP backpressure (and
 * therefore real partial writes) on the sender side, never used as the
 * receiver for any of the other four variants' correctness or throughput
 * measurement.
 */
public final class Receiver {

    private Receiver() {
    }

    private static void readFully(SocketChannel channel, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) {
                throw new IOException("channel closed before " + buf.remaining() + " more bytes were read");
            }
        }
    }

    public static long receive(SocketChannel channel, long messageCount) throws IOException {
        return receive(channel, messageCount, 0);
    }

    /** {@code perMessageDelayMillis > 0} deliberately slows the receiver to induce backpressure. */
    public static long receiveSlowly(SocketChannel channel, long messageCount, long perMessageDelayMillis) throws IOException {
        return receive(channel, messageCount, perMessageDelayMillis);
    }

    private static long receive(SocketChannel channel, long messageCount, long perMessageDelayMillis) throws IOException {
        long delivered = 0;
        ByteBuffer header = ByteBuffer.allocate(MessageFormat.HEADER_SIZE);
        for (long i = 0; i < messageCount; i++) {
            header.clear();
            readFully(channel, header);
            byte[] headerBytes = header.array();
            long seq = MessageFormat.readLong(headerBytes, 0);
            long payloadLength = MessageFormat.readLong(headerBytes, 8);

            ByteBuffer payload = ByteBuffer.allocate((int) payloadLength);
            readFully(channel, payload);
            byte[] payloadBytes = payload.array();
            if (MessageFormat.payloadMatches(payloadBytes, seq)) {
                delivered++;
            }
            if (perMessageDelayMillis > 0) {
                try {
                    Thread.sleep(perMessageDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        }
        return delivered;
    }
}
