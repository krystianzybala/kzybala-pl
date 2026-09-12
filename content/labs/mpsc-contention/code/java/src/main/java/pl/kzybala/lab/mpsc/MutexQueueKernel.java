package pl.kzybala.lab.mpsc;

import java.util.ArrayDeque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The "obviously correct" baseline: a plain {@link ArrayDeque} guarded by
 * a single {@link ReentrantLock}. No atomics, no lock-free reasoning — a
 * producer either gets the lock or blocks/spins waiting for it. This is
 * the mechanism every lock-free variant in this lab is implicitly
 * compared against; it is included so that comparison is explicit and
 * measured rather than assumed.
 */
public final class MutexQueueKernel {
    private MutexQueueKernel() {}

    public static MpscResult run(int producerCount, int itemsPerProducer, int capacity) throws InterruptedException {
        ArrayDeque<Long> queue = new ArrayDeque<>(capacity);
        ReentrantLock lock = new ReentrantLock();

        Thread[] producers = new Thread[producerCount];
        for (int p = 0; p < producerCount; p++) {
            final int producerId = p;
            producers[p] = new Thread(() -> {
                for (int seq = 0; seq < itemsPerProducer; seq++) {
                    long value = MpscFixtures.encode(producerId, seq);
                    long spins = 0;
                    while (true) {
                        lock.lock();
                        try {
                            if (queue.size() < capacity) {
                                queue.addLast(value);
                                break;
                            }
                        } finally {
                            lock.unlock();
                        }
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
            while (receivedCount < total) {
                Long value;
                lock.lock();
                try {
                    value = queue.pollFirst();
                } finally {
                    lock.unlock();
                }
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
