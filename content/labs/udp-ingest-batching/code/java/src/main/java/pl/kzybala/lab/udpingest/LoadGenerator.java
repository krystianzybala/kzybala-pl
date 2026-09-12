package pl.kzybala.lab.udpingest;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Sends {@code messageCount} deterministic datagrams to a loopback
 * (127.0.0.1) UDP port. This lab tests exclusively over loopback and
 * labels that fact everywhere a result appears — see the "testing only
 * loopback without labeling" trap: loopback has no real network latency,
 * jitter, or link-layer loss, so its numbers characterize the receive
 * path's own mechanism, not real-network UDP behavior.
 */
public final class LoadGenerator {

    private final InetAddress loopback;
    private final int port;

    public LoadGenerator(int port) throws IOException {
        this.loopback = InetAddress.getLoopbackAddress();
        this.port = port;
    }

    /** Sends messages back-to-back with no pacing — the "overload"/burst-style profile. */
    public void sendBurst(long messageCount, int datagramSize) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            for (long seq = 0; seq < messageCount; seq++) {
                byte[] data = DatagramFormat.build(seq, datagramSize);
                socket.send(new DatagramPacket(data, data.length, loopback, port));
            }
        }
    }

    /** Sends messages with an explicit inter-send delay — the "steady rate" profile. */
    public void sendSteady(long messageCount, int datagramSize, long interSendNanos) throws IOException, InterruptedException {
        try (DatagramSocket socket = new DatagramSocket()) {
            long nextSend = System.nanoTime();
            for (long seq = 0; seq < messageCount; seq++) {
                byte[] data = DatagramFormat.build(seq, datagramSize);
                socket.send(new DatagramPacket(data, data.length, loopback, port));
                nextSend += interSendNanos;
                long sleepNanos = nextSend - System.nanoTime();
                if (sleepNanos > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(sleepNanos);
                }
            }
        }
    }

    /**
     * Sends a fast run followed by an idle gap, repeated — the "microburst"
     * profile: bursts of {@code burstSize} messages with no inter-send
     * delay, separated by {@code gapNanos} of idle time.
     */
    public void sendMicrobursts(long messageCount, int datagramSize, int burstSize, long gapNanos)
            throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            long seq = 0;
            while (seq < messageCount) {
                long burstEnd = Math.min(seq + burstSize, messageCount);
                for (; seq < burstEnd; seq++) {
                    byte[] data = DatagramFormat.build(seq, datagramSize);
                    socket.send(new DatagramPacket(data, data.length, loopback, port));
                }
                if (seq < messageCount) {
                    java.util.concurrent.locks.LockSupport.parkNanos(gapNanos);
                }
            }
        }
    }
}
