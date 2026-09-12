package pl.kzybala.lab.mpsc;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reference point, not a lab-authored mechanism: the JDK's own
 * {@link ConcurrentLinkedQueue}, an unbounded lock-free queue safe for
 * multiple producers and multiple consumers. Used exactly as shipped —
 * no reimplementation, no tuning — specifically so this lab has one
 * variant that is neither bounded nor hand-built, matching the design's
 * "unbounded library queue comparison" requirement. Boxing every
 * {@code long} into a {@code Long} here is the JDK API's own cost, not
 * an artificial handicap introduced by this lab: it is disclosed exactly
 * because it is real, not smoothed over.
 */
public final class LibraryQueueKernel {
    private LibraryQueueKernel() {}

    public static MpscResult run(int producerCount, int itemsPerProducer) throws InterruptedException {
        ConcurrentLinkedQueue<Long> queue = new ConcurrentLinkedQueue<>();

        Thread[] producers = new Thread[producerCount];
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producers[p] = new Thread(() -> {
                for (int seq = 0; seq < itemsPerProducer; seq++) {
                    queue.offer(MpscFixtures.encode(producerId, seq)); // unbounded — never rejected
                }
            });
        }

        int total = producerCount * itemsPerProducer;
        long[] received = new long[total];
        Thread consumer = new Thread(() -> {
            int receivedCount = 0;
            long spins = 0;
            while (receivedCount < total) {
                Long value = queue.poll();
                if (value != null) {
                    received[receivedCount++] = value;
                    spins = 0;
                } else if (++spins >= MpscFixtures.MAX_SPIN_ITERATIONS) {
                    throw new IllegalStateException("consumer spun out waiting for data, received " + receivedCount + "/" + total);
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
