package pl.kzybala.lab.vpe;

import java.util.concurrent.locks.LockSupport;

/**
 * Deterministic, allocation-free work primitives shared by every variant runner.
 * cpuChecksum is a pure function of (id, iterations) — its result never depends on
 * which thread, executor or scheduling model ran it, which is exactly what lets the
 * correctness fixture assert one expected total regardless of variant.
 */
public final class Work {
    private static final long SEED_MULTIPLIER = 0x9E3779B97F4A7C15L;

    private Work() {}

    public static long cpuChecksum(int id, int iterations) {
        long x = id * SEED_MULTIPLIER + 1L; // relies on Java's silent 64-bit wraparound
        for (int i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    public static void blockingWait(long nanos) {
        if (nanos > 0) {
            LockSupport.parkNanos(nanos);
        }
    }

    private static final Object PIN_MONITOR = new Object();

    /** Runs wait+checksum inside a synchronized monitor — the historical virtual-thread pinning shape. */
    public static long runLocked(TaskSpec task) {
        synchronized (PIN_MONITOR) {
            blockingWait(task.ioWaitNanos());
            return cpuChecksum(task.id(), task.cpuIterations());
        }
    }
}
