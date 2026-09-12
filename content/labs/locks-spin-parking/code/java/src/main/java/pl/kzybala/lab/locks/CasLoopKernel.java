package pl.kzybala.lab.locks;

import java.util.concurrent.atomic.AtomicLong;

/**
 * No lock at all: every worker retries a compare-and-swap on a shared
 * {@link AtomicLong} until it succeeds. Correct and genuinely lock-free,
 * but every failed attempt is a wasted read-modify-write racing every
 * other worker — the direct point of comparison against the mutex
 * variants' blocking/spinning cost.
 */
public final class CasLoopKernel {
    private CasLoopKernel() {}

    public static LockResult run(int workerCount, int opsPerWorker) throws InterruptedException {
        AtomicLong counter = new AtomicLong(0);
        long[] casFailures = new long[workerCount];

        Thread[] workers = new Thread[workerCount];
        for (int w = 0; w < workerCount; w++) {
            final int workerId = w;
            workers[w] = new Thread(() -> {
                long localFailures = 0;
                for (int i = 0; i < opsPerWorker; i++) {
                    while (true) {
                        long cur = counter.get();
                        if (counter.compareAndSet(cur, cur + 1)) {
                            break;
                        }
                        localFailures++;
                    }
                }
                casFailures[workerId] = localFailures;
            });
        }

        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        long totalFailures = 0;
        for (long f : casFailures) totalFailures += f;
        return new LockResult(counter.get(), totalFailures, 0);
    }
}
