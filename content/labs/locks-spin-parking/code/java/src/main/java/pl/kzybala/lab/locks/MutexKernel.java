package pl.kzybala.lab.locks;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Three of this lab's five variants share one mechanism —
 * {@link ReentrantLock}-protected increments of a shared counter — and
 * differ only in worker count and critical-section length:
 * <ul>
 *   <li>{@link #uncontended()} — a single worker; the lock is acquired
 *   and released {@code opsPerWorker} times but never contended, the
 *   baseline "what does a lock cost when nobody is waiting" case;</li>
 *   <li>{@link #shortContended(int, int)} — {@code workerCount} workers,
 *   a critical section that does only the increment itself;</li>
 *   <li>{@link #longCriticalSection(int, int, int)} — the same
 *   contention, but the critical section additionally performs
 *   {@code busyIterations} of deterministic busy work before releasing,
 *   holding the lock longer without changing correctness.</li>
 * </ul>
 */
public final class MutexKernel {
    private MutexKernel() {}

    private static LockResult run(int workerCount, int opsPerWorker, int busyIterations) throws InterruptedException {
        ReentrantLock lock = new ReentrantLock();
        long[] counter = {0};
        long[] sink = {0}; // absorbs busyWork's result so it cannot be dead-code-eliminated

        Thread[] workers = new Thread[workerCount];
        for (int w = 0; w < workerCount; w++) {
            workers[w] = new Thread(() -> {
                long localSink = 0;
                for (int i = 0; i < opsPerWorker; i++) {
                    lock.lock();
                    try {
                        if (busyIterations > 0) {
                            localSink += LocksFixtures.busyWork(busyIterations);
                        }
                        counter[0]++;
                    } finally {
                        lock.unlock();
                    }
                }
                synchronized (sink) {
                    sink[0] += localSink;
                }
            });
        }

        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        return new LockResult(counter[0], 0, 0);
    }

    public static LockResult uncontended() throws InterruptedException {
        return run(1, LocksFixtures.OPS_PER_WORKER, 0);
    }

    public static LockResult shortContended(int workerCount, int opsPerWorker) throws InterruptedException {
        return run(workerCount, opsPerWorker, 0);
    }

    public static LockResult longCriticalSection(int workerCount, int opsPerWorker, int busyIterations) throws InterruptedException {
        return run(workerCount, opsPerWorker, busyIterations);
    }
}
