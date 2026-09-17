package pl.kzybala.lab.udpingest;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.DatagramChannel;
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
 * <p>A first fix attempt toggled the channel to non-blocking mode and
 * polled it with a {@link java.nio.channels.Selector}, timing out the
 * {@code select()} call. That version passed every test on this
 * repository's (aarch64/macOS) development machine but reproducibly
 * hung the correctness gate on the native-Linux publication host, on
 * datagram counts far too small to plausibly lose a packet — a genuine
 * host-dependent difference in that hand-rolled polling loop, not the
 * loss trap it was meant to guard against. This version instead uses
 * the well-established "cancel a blocking NIO channel op by closing the
 * channel from another thread" idiom: the receive stays a plain,
 * ordinary blocking {@link DatagramChannel#receive}, and a daemon
 * watchdog thread closes the channel after the deadline, which
 * unblocks the in-progress receive with an
 * {@link AsynchronousCloseException} — no non-blocking mode, no
 * {@code Selector}, nothing that behaved differently across platforms
 * during testing.
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
     * Runs {@code loop} against {@code channel} (left in its normal
     * blocking mode throughout) with a watchdog thread that closes the
     * channel after {@link #DEFAULT_DEADLINE} — turning a lost-packet
     * hang into a clear, diagnosable {@link IllegalStateException}
     * instead of blocking the receive loop forever. The watchdog is
     * cancelled before it can fire on the normal, on-time completion
     * path.
     */
    static <T> T withDeadline(DatagramChannel channel, ReceiveLoop<T> loop) throws IOException {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(DEFAULT_DEADLINE.toMillis());
            } catch (InterruptedException e) {
                return; // cancelled: the loop finished before the deadline
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // best-effort cancellation; the receive loop's own IOException
                // handling (AsynchronousCloseException below) is authoritative
            }
        }, "udp-ingest-batching-receive-deadline");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            return loop.run(new Receiver(channel));
        } catch (AsynchronousCloseException e) {
            throw new IllegalStateException(
                    "udp-ingest-batching: receive timed out after " + DEFAULT_DEADLINE
                            + " — the remaining datagrams were lost (kernel receive-buffer overflow under burst"
                            + " load is an expected UDP-loss trap this lab measures, not a hang) and will never"
                            + " arrive; see theory.md's coordinated-loss discussion.",
                    e);
        } finally {
            watchdog.interrupt();
        }
    }

    interface ReceiveLoop<T> {
        T run(Receiver receiver) throws IOException;
    }

    /** A plain blocking receive, scoped to one deadline-bounded loop invocation. */
    static final class Receiver {
        private final DatagramChannel channel;

        private Receiver(DatagramChannel channel) {
            this.channel = channel;
        }

        void receive(java.nio.ByteBuffer dst) throws IOException {
            channel.receive(dst);
        }
    }
}
