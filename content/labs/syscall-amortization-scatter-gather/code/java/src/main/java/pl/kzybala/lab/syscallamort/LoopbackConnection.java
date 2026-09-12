package pl.kzybala.lab.syscallamort;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * A connected pair of {@link SocketChannel}s over TCP loopback
 * (127.0.0.1) — every benchmark and test in this lab uses loopback only.
 */
public final class LoopbackConnection implements AutoCloseable {

    public final SocketChannel client;
    public final SocketChannel server;
    private final ServerSocketChannel listener;

    private LoopbackConnection(SocketChannel client, SocketChannel server, ServerSocketChannel listener) {
        this.client = client;
        this.server = server;
        this.listener = listener;
    }

    public static LoopbackConnection open() throws IOException {
        ServerSocketChannel listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        int port = ((java.net.InetSocketAddress) listener.getLocalAddress()).getPort();

        SocketChannel client = SocketChannel.open(new InetSocketAddress("127.0.0.1", port));
        SocketChannel server = listener.accept();
        client.configureBlocking(true);
        server.configureBlocking(true);
        return new LoopbackConnection(client, server, listener);
    }

    @Override
    public void close() throws IOException {
        client.close();
        server.close();
        listener.close();
    }
}
