package pl.kzybala.lab.memord;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Counter update: MemOrdFixtures.COUNTER_THREAD_COUNT threads each
 * perform MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD increments on a
 * shared counter. This dataset's central teaching point is the
 * "volatile/seq-cst publication" trap: a volatile/seq-cst FIELD does
 * NOT make a read-modify-write compound operation atomic — only a true
 * RMW primitive (getAndAdd family, or CAS-guarded retry, or mutual
 * exclusion) prevents lost updates here. Mapping "stronger ordering
 * keyword" to "automatically correct for RMW" is exactly the
 * design.md-named trap this dataset exists to demonstrate.
 */
public final class CounterUpdateKernel {
    private CounterUpdateKernel() {}

    public static final class Result {
        public final long total;
        public final long failedCas;

        Result(long total, long failedCas) {
            this.total = total;
            this.failedCas = failedCas;
        }
    }

    static final class Counter {
        int value;
        int lock; // 0 = free, 1 = held (fence-based variant only)
    }

    private static final VarHandle VALUE;
    private static final VarHandle LOCK;
    static {
        try {
            VALUE = MethodHandles.lookup().findVarHandle(Counter.class, "value", int.class);
            LOCK = MethodHandles.lookup().findVarHandle(Counter.class, "lock", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Result runThreads(Runnable perThreadWork, Counter c, long failedCas)
            throws InterruptedException {
        Thread[] threads = new Thread[MemOrdFixtures.COUNTER_THREAD_COUNT];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(perThreadWork);
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        return new Result(c.value, failedCas);
    }

    /** Deliberately NOT asserted to equal the expected total in the
     * correctness suite — lost updates under contention are real but
     * their exact magnitude is not reproducible run-to-run; the
     * observed total is reported honestly instead of gated on. */
    public static Result plainBrokenPublication() throws InterruptedException {
        Counter c = new Counter();
        Runnable work = () -> {
            for (int i = 0; i < MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD; i++) {
                c.value = c.value + 1; // plain read-modify-write, not atomic
            }
        };
        return runThreads(work, c, 0);
    }

    public static Result acquireReleasePublication() throws InterruptedException {
        Counter c = new Counter();
        Runnable work = () -> {
            for (int i = 0; i < MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD; i++) {
                VALUE.getAndAddRelease(c, 1); // genuine atomic RMW, release-ordered
            }
        };
        return runThreads(work, c, 0);
    }

    /** The trap variant: a seq-cst FIELD accessed via a separate
     * getVolatile then setVolatile is NOT an atomic RMW — the ordering
     * strength of the individual accesses does not make the two-step
     * compound operation indivisible. Expect real lost updates here,
     * same mechanism as the plain variant, despite the stronger-sounding
     * name. */
    public static Result volatileSeqCstPublication() throws InterruptedException {
        Counter c = new Counter();
        Runnable work = () -> {
            for (int i = 0; i < MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD; i++) {
                int cur = (int) VALUE.getVolatile(c);
                VALUE.setVolatile(c, cur + 1); // two separate ops, not one atomic RMW
            }
        };
        return runThreads(work, c, 0);
    }

    public static Result casLoop() throws InterruptedException {
        Counter c = new Counter();
        long[] failedCasPerThread = new long[MemOrdFixtures.COUNTER_THREAD_COUNT];
        Thread[] threads = new Thread[MemOrdFixtures.COUNTER_THREAD_COUNT];
        for (int t = 0; t < threads.length; t++) {
            final int idx = t;
            threads[t] = new Thread(() -> {
                long failures = 0;
                for (int i = 0; i < MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD; i++) {
                    while (true) {
                        int cur = (int) VALUE.getVolatile(c);
                        if (VALUE.compareAndSet(c, cur, cur + 1)) {
                            break;
                        }
                        failures++;
                    }
                }
                failedCasPerThread[idx] = failures;
            });
        }
        for (Thread th : threads) th.start();
        for (Thread th : threads) th.join();
        long totalFailed = 0;
        for (long f : failedCasPerThread) totalFailed += f;
        return new Result(c.value, totalFailed);
    }

    /** Hand-rolled test-and-CAS spinlock guarding a plain increment —
     * mutual exclusion instead of CAS-loop's lock-free retry. Correct,
     * via a genuinely different mechanism. */
    public static Result fenceBased() throws InterruptedException {
        Counter c = new Counter();
        Runnable work = () -> {
            for (int i = 0; i < MemOrdFixtures.COUNTER_INCREMENTS_PER_THREAD; i++) {
                while (!LOCK.compareAndSet(c, 0, 1)) {
                    Thread.onSpinWait();
                }
                c.value = c.value + 1; // plain, protected by the lock's own full-fence CAS
                LOCK.setRelease(c, 0);
            }
        };
        return runThreads(work, c, 0);
    }
}
