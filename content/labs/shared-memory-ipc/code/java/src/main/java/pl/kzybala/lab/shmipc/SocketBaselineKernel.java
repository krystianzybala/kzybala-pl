package pl.kzybala.lab.shmipc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Variant: pipe/socket baseline. A loopback TCP connection carries the same
 * fixed messages the shared-memory variants carry. Every message crosses
 * the real kernel network stack (loopback socket I/O is a genuine syscall
 * path, not a JVM-heap shortcut) exactly as it would between two processes
 * — the two-thread structure here is a benchmarking convenience for the
 * socket variant specifically, not the "same-process threads and calling
 * it IPC" trap this lab documents, because sockets do not have that
 * trap's failure mode (the kernel enforces the same syscall/copy path
 * whether the two ends are threads or processes).
 */
public final class SocketBaselineKernel {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /** Sends {@code messageCount} deterministic messages over a fresh loopback socket pair; returns the receiver's checksum. */
    public long runOnce(long messageCount, int payloadSize) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Callable<Long> receiverTask = () -> {
                try (Socket accepted = server.accept();
                     InputStream in = accepted.getInputStream()) {
                    byte[] buf = new byte[payloadSize];
                    long checksum = 0;
                    for (long i = 0; i < messageCount; i++) {
                        readFully(in, buf, payloadSize);
                        checksum += MessageFixtures.checksum(i, buf, payloadSize);
                    }
                    return checksum;
                }
            };
            Future<Long> receiverFuture = executor.submit(receiverTask);

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port);
                 OutputStream out = client.getOutputStream()) {
                for (long i = 0; i < messageCount; i++) {
                    out.write(MessageFixtures.payload(i, payloadSize));
                }
                out.flush();
            }

            return receiverFuture.get();
        }
    }

    private static void readFully(InputStream in, byte[] buf, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int n = in.read(buf, total, length - total);
            if (n < 0) {
                throw new IOException("unexpected EOF after " + total + "/" + length + " bytes");
            }
            total += n;
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
