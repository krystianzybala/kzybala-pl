package pl.kzybala.lab.udpingest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Variant: zero-copy view handoff with a bounded lifetime. A slab of
 * {@code slabSize} preallocated direct buffers is reused round-robin;
 * the receiver calls {@code channel.receive(...)} directly into a slab
 * slot (no copy at all) and hands the consumer only that slot's index.
 * The slab size equals the bounded queue's capacity, which is exactly
 * what makes the "bounded lifetime" safe: the receiver only reuses slot
 * {@code i % slabSize} once it is certain slot {@code i} has already been
 * consumed — checked BEFORE receiving into it, never after — so a
 * consumer's view is never overwritten while still in use. If the
 * consumer is behind and the target slot is still in use, the datagram
 * is still drained off the socket (into a small scratch buffer, not the
 * slab) and explicitly counted as an application-level drop — this is
 * exactly the discipline needed to avoid the "reusing receive buffer
 * after publication" trap while still avoiding "unbounded queues hiding
 * loss."
 */
public final class ZeroCopyHandoffPipeline implements AutoCloseable {

    private final DatagramChannel channel;
    private final int slabSize;

    public ZeroCopyHandoffPipeline(int port, int slabSize) throws IOException {
        this.channel = DatagramChannel.open();
        channel.setOption(StandardSocketOptions.SO_RCVBUF, 4 * 1024 * 1024);
        channel.bind(new InetSocketAddress("127.0.0.1", port));
        this.slabSize = slabSize;
    }

    public void close() throws IOException {
        channel.close();
    }

    public IngestResult run(long messageCount, int datagramSize) throws Exception {
        ByteBuffer[] slab = new ByteBuffer[slabSize];
        for (int i = 0; i < slabSize; i++) {
            slab[i] = ByteBuffer.allocateDirect(datagramSize);
        }
        ByteBuffer discardBuf = ByteBuffer.allocateDirect(datagramSize);

        ArrayBlockingQueue<Integer> readyIndices = new ArrayBlockingQueue<>(slabSize);
        // Tracks how many receives have completed into slot i since the
        // consumer last finished with it — used to decide, BEFORE
        // receiving, whether slot (seq % slabSize) is safe to reuse.
        boolean[] inFlight = new boolean[slabSize];

        java.util.concurrent.atomic.AtomicLong delivered = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong corrupted = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong applicationDropped = new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch consumerDone = new CountDownLatch(1);
        java.util.concurrent.ConcurrentLinkedQueue<Integer> freedSlots = new java.util.concurrent.ConcurrentLinkedQueue<>();
        int poisonIndex = -1;

        Thread consumer = new Thread(() -> {
            try {
                while (true) {
                    int idx = readyIndices.take();
                    if (idx == poisonIndex) {
                        break;
                    }
                    ByteBuffer view = slab[idx].duplicate();
                    view.flip();
                    int length = view.remaining();
                    byte[] scratch = new byte[length];
                    view.get(scratch);
                    long seq = DatagramFormat.readSequence(scratch, 0);
                    if (DatagramFormat.payloadMatches(scratch, 0, length, seq)) {
                        delivered.incrementAndGet();
                    } else {
                        corrupted.incrementAndGet();
                    }
                    freedSlots.add(idx);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        }, "udp-ingest-zero-copy-consumer");
        consumer.start();

        BoundedReceive.withDeadline(channel, receiver -> {
            for (long i = 0; i < messageCount; i++) {
                int idx = (int) (i % slabSize);
                if (inFlight[idx]) {
                    // Not yet freed by the consumer — receiving into it now would
                    // violate the bounded-lifetime contract. Drain the socket into
                    // a scratch buffer instead and count an explicit drop.
                    discardBuf.clear();
                    receiver.receive(discardBuf);
                    applicationDropped.incrementAndGet();
                    continue;
                }
                slab[idx].clear();
                receiver.receive(slab[idx]);
                inFlight[idx] = true;
                try {
                    readyIndices.put(idx);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while queuing a ready slot index", e);
                }
                // Reclaim any slots the consumer has finished with since we last checked.
                Integer freed;
                while ((freed = freedSlots.poll()) != null) {
                    inFlight[freed] = false;
                }
            }
            return null;
        });
        readyIndices.put(poisonIndex);
        consumerDone.await();

        return new IngestResult(delivered.get(), applicationDropped.get(), corrupted.get());
    }
}
