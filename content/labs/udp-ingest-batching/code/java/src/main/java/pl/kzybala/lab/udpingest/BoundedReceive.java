package pl.kzybala.lab.udpingest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Duration;

/**
 * A real bug found while running this lab's benchmark on the
 * native-Linux publication host: every receive loop here waits for a
 * fixed message count over UDP, a protocol with no delivery guarantee —
 * not even on loopback. {@code sendBurst}'s unpaced send loop can
 * overflow the kernel's UDP receive buffer under real host load (the
 * `large1400Byte` profile alone sends ~5.6 MB back-to-back against a
 * default several-hundred-KB buffer), silently dropping datagrams that
 * will never be retransmitted. A plain blocking
 * {@code channel.receive(buf)} then waits forever for a datagram that no
 * longer exists — a genuine, reproducible hang
 * (`failed-benchmark-timeout` on the batch runner), not a flaky one-off.
 *
 * <p>Every receive loop in this lab must therefore run against an
 * overall wall-clock deadline and fail loudly and diagnosably — never
 * hang silently — matching the "bound every wait with a deadline"
 * discipline already established for the SPSC lab's transfer harness.
 * This wraps one loop invocation's non-blocking receive-with-timeout so
 * every call site pays only one extra {@link Selector#select(long)} per
 * packet, not a fresh {@link Selector} per packet.
 */
final class BoundedReceive {

    private BoundedReceive() {}

    /**
     * Generous relative to this lab's own message counts and datagram
     * sizes (low thousands of packets, well under a second of real
     * receive work) — long enough that a merely slow host never trips
     * it, short enough that a genuine loss-induced hang fails the run
     * instead of hanging the whole publication batch.
     */
    static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(20);

    /**
     * Runs {@code loop} with {@code channel} temporarily in non-blocking
     * mode, registered on a single {@link Selector} for the whole loop
     * (opened and closed once here, not per packet), and restores the
     * channel's prior blocking mode afterward regardless of outcome.
     */
    static <T> T withDeadline(DatagramChannel channel, ReceiveLoop<T> loop) throws IOException {
        boolean wasBlocking = channel.isBlocking();
        Selector selector = Selector.open();
        try {
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_READ);
            long deadlineNanos = System.nanoTime() + DEFAULT_DEADLINE.toNanos();
            return loop.run(new Receiver(channel, selector, deadlineNanos));
        } finally {
            selector.close();
            channel.configureBlocking(wasBlocking);
        }
    }

    interface ReceiveLoop<T> {
        T run(Receiver receiver) throws IOException;
    }

    /** One deadline-bounded receive loop's worth of state, handed to the loop body. */
    static final class Receiver {
        private final DatagramChannel channel;
        private final Selector selector;
        private final long deadlineNanos;

        private Receiver(DatagramChannel channel, Selector selector, long deadlineNanos) {
            this.channel = channel;
            this.selector = selector;
            this.deadlineNanos = deadlineNanos;
        }

        /**
         * Blocks (via {@link Selector#select(long)}, not a busy spin)
         * until a datagram is available or the loop's overall deadline
         * expires, then receives exactly one datagram into {@code dst}.
         *
         * @param receivedSoFar diagnostic only — reported in the timeout message
         * @param expectedTotal diagnostic only — reported in the timeout message
         */
        void receive(ByteBuffer dst, long receivedSoFar, long expectedTotal) throws IOException {
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IllegalStateException(
                            "udp-ingest-batching: receive timed out after " + receivedSoFar + "/" + expectedTotal
                                    + " datagrams over " + DEFAULT_DEADLINE
                                    + " — the remaining datagrams were lost (kernel receive-buffer overflow under"
                                    + " burst load is an expected UDP-loss trap this lab measures, not a hang) and"
                                    + " will never arrive; see theory.md's coordinated-loss discussion.");
                }
                long remainingMillis = Math.max(1, remainingNanos / 1_000_000L);
                selector.select(remainingMillis);
                selector.selectedKeys().clear();
                if (channel.receive(dst) != null) {
                    return;
                }
            }
        }
    }
}
