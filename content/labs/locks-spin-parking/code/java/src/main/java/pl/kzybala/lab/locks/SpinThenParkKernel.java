package pl.kzybala.lab.locks;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * A hand-rolled adaptive lock: a waiter first spins (bounded by
 * {@code spinLimit}), and only falls back to {@link LockSupport#park()}
 * if the lock is still held after spinning — the classic "spin briefly,
 * then sleep" trade-off between wasting CPU and paying a real context
 * switch. This is a teaching implementation, not a production one: the
 * waiter queue is a plain {@link ConcurrentLinkedQueue}, and the
 * double-check-then-park sequence is the standard pattern for avoiding a
 * missed wakeup (park a thread that already missed its unpark signal),
 * not a novel design.
 */
public final class SpinThenParkKernel {
    private SpinThenParkKernel() {}

    static final class SpinParkLock {
        private final AtomicBoolean locked = new AtomicBoolean(false);
        private final ConcurrentLinkedQueue<Thread> waiters = new ConcurrentLinkedQueue<>();
        private final int spinLimit;
        private final AtomicLong parkCount = new AtomicLong(0); // incremented by any parking thread — genuinely shared, hence atomic

        SpinParkLock(int spinLimit) {
            this.spinLimit = spinLimit;
        }

        void lock() {
            int spins = 0;
            while (true) {
                if (locked.compareAndSet(false, true)) {
                    return;
                }
                if (spins < spinLimit) {
                    spins++;
                    Thread.onSpinWait();
                    continue;
                }
                Thread self = Thread.currentThread();
                waiters.add(self);
                // Double-check after registering: if the lock freed up
                // between the last failed CAS and joining the waiter
                // queue, take it now rather than parking with no one
                // left to wake us (the standard missed-wakeup guard).
                if (locked.compareAndSet(false, true)) {
                    waiters.remove(self);
                    return;
                }
                LockSupport.park();
                waiters.remove(self); // idempotent if unlock() already removed us
                parkCount.incrementAndGet();
                spins = 0; // re-enter the spin phase after waking, rather than parking immediately again
            }
        }

        void unlock() {
            locked.set(false);
            Thread w = waiters.poll();
            if (w != null) {
                LockSupport.unpark(w);
            }
        }
    }

    public static LockResult run(int workerCount, int opsPerWorker, int spinLimit) throws InterruptedException {
        SpinParkLock lock = new SpinParkLock(spinLimit);
        long[] counter = {0};

        Thread[] workers = new Thread[workerCount];
        for (int w = 0; w < workerCount; w++) {
            workers[w] = new Thread(() -> {
                for (int i = 0; i < opsPerWorker; i++) {
                    lock.lock();
                    try {
                        counter[0]++;
                    } finally {
                        lock.unlock();
                    }
                }
            });
        }

        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        return new LockResult(counter[0], 0, lock.parkCount.get());
    }
}
