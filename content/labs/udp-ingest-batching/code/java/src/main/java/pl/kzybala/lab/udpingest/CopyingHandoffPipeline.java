package pl.kzybala.lab.udpingest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Variant: copying handoff. The receive loop reuses one direct buffer,
 * but copies every received datagram's bytes into a fresh {@code byte[]}
 * before handing it to a bounded queue — safe to reuse the receive buffer
 * immediately after, at the cost of one allocation and one copy per
 * datagram. A second thread drains the queue, validates each datagram,
 * and counts delivered/corrupted messages. When the bounded queue is
 * full, the receiver still drains the socket (so a slow consumer never
 * causes the OS to back up) but explicitly discards the datagram and
 * counts it as an application-level drop — see the "unbounded queues
 * hiding loss" trap this design avoids by bounding the queue at all.
 */
public final class CopyingHandoffPipeline implements AutoCloseable {

    private final DatagramChannel channel;
    private final int queueCapacity;

    public CopyingHandoffPipeline(int port, int queueCapacity) throws IOException {
        this.channel = DatagramChannel.open();
        channel.setOption(StandardSocketOptions.SO_RCVBUF, 4 * 1024 * 1024);
        channel.bind(new InetSocketAddress("127.0.0.1", port));
        this.queueCapacity = queueCapacity;
    }

    public void close() throws IOException {
        channel.close();
    }

    public IngestResult run(long messageCount, int datagramSize) throws Exception {
        ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(queueCapacity);
        java.util.concurrent.atomic.AtomicLong delivered = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong corrupted = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong applicationDropped = new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch consumerDone = new CountDownLatch(1);
        byte[] poisonPill = new byte[0];

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    byte[] datagram = queue.take();
                    if (datagram == poisonPill) {
                        break;
                    }
                    long seq = DatagramFormat.readSequence(datagram, 0);
                    if (DatagramFormat.payloadMatches(datagram, 0, datagram.length, seq)) {
                        delivered.incrementAndGet();
                    } else {
                        corrupted.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        }, "udp-ingest-copying-consumer");
        consumer.start();

        ByteBuffer buf = ByteBuffer.allocateDirect(datagramSize);
        BoundedReceive.withDeadline(channel, receiver -> {
            for (long i = 0; i < messageCount; i++) {
                buf.clear();
                receiver.receive(buf, i, messageCount);
                buf.flip();
                int length = buf.remaining();
                byte[] copy = new byte[length];
                buf.get(copy);
                if (!queue.offer(copy)) {
                    applicationDropped.incrementAndGet();
                }
            }
            return null;
        });
        queue.put(poisonPill);
        consumerDone.await();

        return new IngestResult(delivered.get(), applicationDropped.get(), corrupted.get());
    }
}
