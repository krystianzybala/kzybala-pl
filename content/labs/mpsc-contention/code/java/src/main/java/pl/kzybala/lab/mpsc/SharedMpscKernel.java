package pl.kzybala.lab.mpsc;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Single shared bounded MPSC queue using Dmitry Vyukov's bounded
 * multi-producer/single-consumer ring: each cell carries its own
 * sequence number, producers claim a position with a CAS retry loop on
 * the shared {@code enqueuePos}, and the per-cell sequence both signals
 * "ready to read" to the consumer and "free to reuse" back to producers
 * — this is the shared-structure baseline every other variant in this
 * lab is contrasted against, and the one where every single-item
 * enqueue pays a real CAS attempt (successful or not) on the same
 * shared cache line.
 */
public final class SharedMpscKernel {
    private SharedMpscKernel() {}

    static final class Ring {
        final int capacity;
        final int mask;
        final AtomicLongArray sequence;
        final long[] data;
        final AtomicLong enqueuePos = new AtomicLong(0);

        Ring(int capacity) {
            if (Integer.bitCount(capacity) != 1) throw new IllegalArgumentException("capacity must be a power of two");
            this.capacity = capacity;
            this.mask = capacity - 1;
            this.sequence = new AtomicLongArray(capacity);
            this.data = new long[capacity];
            for (int i = 0; i < capacity; i++) sequence.set(i, i);
        }
    }

    public static MpscResult run(int producerCount, int itemsPerProducer, int capacity) throws InterruptedException {
        Ring ring = new Ring(capacity);
        long[] casFailures = new long[producerCount];

        Thread[] producers = new Thread[producerCount];
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producers[p] = new Thread(() -> {
                long localFailures = 0;
                for (int seq = 0; seq < itemsPerProducer; seq++) {
                    long value = MpscFixtures.encode(producerId, seq);
                    long spins = 0;
                    while (true) {
                        long pos = ring.enqueuePos.get();
                        int idx = (int) (pos & ring.mask);
                        long cellSeq = ring.sequence.get(idx);
                        long diff = cellSeq - pos;
                        if (diff == 0) {
                            if (ring.enqueuePos.compareAndSet(pos, pos + 1)) {
                                ring.data[idx] = value;          // payload write
                                ring.sequence.set(idx, pos + 1); // publish (release, piggybacking the data write)
                                break;
                            }
                            localFailures++; // lost the race to another producer
                        } else if (diff < 0) {
                            // ring full — consumer hasn't freed this cell yet
                        }
                        if (++spins >= MpscFixtures.MAX_SPIN_ITERATIONS) {
                            throw new IllegalStateException("producer " + producerId + " spun out waiting for capacity");
                        }
                        if ((spins & 0xFFFF) == 0) Thread.onSpinWait();
                    }
                }
                casFailures[producerId] = localFailures;
            });
        }

        int total = producerCount * itemsPerProducer;
        long[] received = new long[total];
        Thread consumer = new Thread(() -> {
            long dequeuePos = 0;
            int receivedCount = 0;
            long spins = 0;
            while (receivedCount < total) {
                int idx = (int) (dequeuePos & ring.mask);
                long cellSeq = ring.sequence.get(idx);
                long diff = cellSeq - (dequeuePos + 1);
                if (diff == 0) {
                    received[receivedCount++] = ring.data[idx];              // payload read
                    ring.sequence.set(idx, dequeuePos + ring.capacity);   // free the cell for producers
                    dequeuePos++;
                    spins = 0;
                } else {
                    if (++spins >= MpscFixtures.MAX_SPIN_ITERATIONS) {
                        throw new IllegalStateException("consumer spun out waiting for data, received " + receivedCount + "/" + total);
                    }
                    if ((spins & 0xFFFF) == 0) Thread.onSpinWait();
                }
            }
        });

        consumer.start();
        for (Thread t : producers) t.start();
        for (Thread t : producers) t.join();
        consumer.join();

        long totalFailures = 0;
        for (long f : casFailures) totalFailures += f;
        return new MpscResult(received, totalFailures);
    }
}
