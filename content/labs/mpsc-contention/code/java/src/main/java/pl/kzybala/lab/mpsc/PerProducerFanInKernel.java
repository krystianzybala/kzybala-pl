package pl.kzybala.lab.mpsc;

/**
 * No shared structure at all: each producer owns a private, single-
 * producer/single-consumer ring buffer (the same reservation/publication
 * discipline as this site's <a href="/lab/spsc-ring-buffer/">SPSC Ring
 * Buffer</a> lab, one instance per producer). The consumer round-robins
 * across all N per-producer rings. There is zero cross-producer
 * contention by construction — the trade-off is that the consumer now
 * has to poll N independent structures instead of draining one, and
 * fairness/occupancy is whatever the round-robin schedule happens to
 * produce.
 */
public final class PerProducerFanInKernel {
    private PerProducerFanInKernel() {}

    static final class SpscRing {
        final int mask;
        final long[] data;
        volatile long head = 0; // published cursor (producer writes)
        volatile long tail = 0; // acknowledged cursor (consumer writes)

        SpscRing(int capacity) {
            this.mask = capacity - 1;
            this.data = new long[capacity];
        }

        boolean tryProduce(long value) {
            long h = head, t = tail;
            if (h - t == data.length) return false; // full
            data[(int) (h & mask)] = value;
            head = h + 1; // release via subsequent volatile write ordering (single writer)
            return true;
        }

        long tryConsume() {
            long t = tail, h = head;
            if (t == h) return Long.MIN_VALUE; // empty sentinel
            long value = data[(int) (t & mask)];
            tail = t + 1;
            return value;
        }
    }

    public static MpscResult run(int producerCount, int itemsPerProducer, int capacity) throws InterruptedException {
        SpscRing[] rings = new SpscRing[producerCount];
        for (int i = 0; i < producerCount; i++) rings[i] = new SpscRing(capacity);

        Thread[] producers = new Thread[producerCount];
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producers[p] = new Thread(() -> {
                SpscRing ring = rings[producerId];
                for (int seq = 0; seq < itemsPerProducer; seq++) {
                    long value = MpscFixtures.encode(producerId, seq);
                    long spins = 0;
                    while (!ring.tryProduce(value)) {
                        if (++spins >= MpscFixtures.MAX_SPIN_ITERATIONS) {
                            throw new IllegalStateException("producer " + producerId + " spun out waiting for capacity");
                        }
                        if ((spins & 0xFFFF) == 0) Thread.onSpinWait();
                    }
                }
            });
        }

        int total = producerCount * itemsPerProducer;
        long[] received = new long[total];
        Thread consumer = new Thread(() -> {
            int receivedCount = 0;
            long spins = 0;
            int cursor = 0;
            while (receivedCount < total) {
                SpscRing ring = rings[cursor];
                cursor = (cursor + 1) % rings.length;
                long value = ring.tryConsume();
                if (value != Long.MIN_VALUE) {
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
