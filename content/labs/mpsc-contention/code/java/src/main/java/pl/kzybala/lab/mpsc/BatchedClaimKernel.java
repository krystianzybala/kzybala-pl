package pl.kzybala.lab.mpsc;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Same bounded ring shape as {@link SharedMpscKernel}, but a producer
 * reserves {@code batchSize} contiguous positions with a single
 * {@code getAndAdd} instead of retrying a per-item CAS — one atomic
 * read-modify-write instead of (at least) one per item. Each reserved
 * cell still has to wait, individually, for the consumer to have freed
 * it (the ring's bounded-capacity backpressure is unchanged); what
 * batching removes is contention on the claim itself, not backpressure
 * waiting.
 */
public final class BatchedClaimKernel {
    private BatchedClaimKernel() {}

    public static MpscResult run(int producerCount, int itemsPerProducer, int capacity, int batchSize) throws InterruptedException {
        SharedMpscKernel.Ring ring = new SharedMpscKernel.Ring(capacity);
        AtomicLong enqueuePos = ring.enqueuePos;

        Thread[] producers = new Thread[producerCount];
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producers[p] = new Thread(() -> {
                int seq = 0;
                while (seq < itemsPerProducer) {
                    int thisBatch = Math.min(batchSize, itemsPerProducer - seq);
                    long basePos = enqueuePos.getAndAdd(thisBatch); // one atomic RMW claims the whole batch
                    for (int i = 0; i < thisBatch; i++) {
                        long pos = basePos + i;
                        int idx = (int) (pos & ring.mask);
                        long spins = 0;
                        while (ring.sequence.get(idx) != pos) {
                            // wait for the consumer to free this specific cell
                            if (++spins >= MpscFixtures.MAX_SPIN_ITERATIONS) {
                                throw new IllegalStateException("producer " + producerId + " spun out waiting for capacity");
                            }
                            if ((spins & 0xFFFF) == 0) Thread.onSpinWait();
                        }
                        ring.data[idx] = MpscFixtures.encode(producerId, seq + i);
                        ring.sequence.set(idx, pos + 1); // publish
                    }
                    seq += thisBatch;
                }
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
                long diff = ring.sequence.get(idx) - (dequeuePos + 1);
                if (diff == 0) {
                    received[receivedCount++] = ring.data[idx];
                    ring.sequence.set(idx, dequeuePos + ring.capacity);
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

        return new MpscResult(received, 0);
    }
}
