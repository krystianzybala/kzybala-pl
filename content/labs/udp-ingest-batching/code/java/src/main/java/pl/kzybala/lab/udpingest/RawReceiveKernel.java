package pl.kzybala.lab.udpingest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * Variants: one-packet-per-receive and reused-direct-buffer. Both measure
 * raw receive-loop cost with NO downstream handoff at all (no queue, no
 * second thread) — every received datagram's sequence is validated
 * in-line and immediately discarded. This isolates the receive call's own
 * cost (and, for the first variant, its per-receive allocation cost) from
 * any bounded-queue/handoff cost, which {@link CopyingHandoffPipeline} and
 * {@link ZeroCopyHandoffPipeline} measure separately.
 */
public final class RawReceiveKernel implements AutoCloseable {

    private final DatagramChannel channel;

    public RawReceiveKernel(int port) throws IOException {
        this.channel = DatagramChannel.open();
        // Widen the kernel receive buffer well beyond this lab's own
        // largest burst (see BoundedReceive) to make real packet loss
        // rarer in practice — the deadline below is the correctness
        // backstop, this is the performance mitigation.
        channel.setOption(StandardSocketOptions.SO_RCVBUF, 4 * 1024 * 1024);
        channel.bind(new InetSocketAddress("127.0.0.1", port));
    }

    public void close() throws IOException {
        channel.close();
    }

    /** Allocates a fresh direct buffer for every single receive call. */
    public long receiveAllocatingPerPacket(long messageCount, int datagramSize) throws IOException {
        return BoundedReceive.withDeadline(channel, receiver -> {
            long validated = 0;
            for (long i = 0; i < messageCount; i++) {
                ByteBuffer buf = ByteBuffer.allocateDirect(datagramSize);
                receiver.receive(buf, i, messageCount);
                buf.flip();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                long seq = DatagramFormat.readSequence(bytes, 0);
                if (DatagramFormat.payloadMatches(bytes, 0, bytes.length, seq)) {
                    validated++;
                }
            }
            return validated;
        });
    }

    /** Reuses one direct buffer across every receive call — no per-packet allocation. */
    public long receiveWithReusedBuffer(long messageCount, int datagramSize) throws IOException {
        return BoundedReceive.withDeadline(channel, receiver -> {
            ByteBuffer buf = ByteBuffer.allocateDirect(datagramSize);
            byte[] scratch = new byte[datagramSize];
            long validated = 0;
            for (long i = 0; i < messageCount; i++) {
                buf.clear();
                receiver.receive(buf, i, messageCount);
                buf.flip();
                int length = buf.remaining();
                buf.get(scratch, 0, length);
                long seq = DatagramFormat.readSequence(scratch, 0);
                if (DatagramFormat.payloadMatches(scratch, 0, length, seq)) {
                    validated++;
                }
            }
            return validated;
        });
    }
}
