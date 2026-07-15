package pl.kzybala.lab.cascontention;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic correctness gate for every benchmarked CAS variant — runs
 * before any timing is trusted (docs/benchmark-correctness-fixtures.md).
 * The retry-with-backoff loops here mirror the evidence benchmark's
 * policies exactly: whatever the retry spacing, the final count must be
 * exact.
 */
class CasCorrectnessTest {

    private static final int THREADS = 4;
    private static final long INCREMENTS_PER_THREAD = 50_000;
    private static final long EXPECTED_TOTAL = THREADS * INCREMENTS_PER_THREAD;

    private interface Backoff {
        void afterFailure(int streak);
    }

    private long runContended(Backoff backoff) throws InterruptedException {
        AtomicLong shared = new AtomicLong();
        Thread[] workers = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            workers[t] = new Thread(() -> {
                int streak = 0;
                for (long i = 0; i < INCREMENTS_PER_THREAD; i++) {
                    for (;;) {
                        long current = shared.get();
                        if (shared.compareAndSet(current, current + 1)) {
                            streak = 0;
                            break;
                        }
                        streak = Math.min(streak + 1, 6);
                        backoff.afterFailure(streak);
                    }
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) worker.join();
        return shared.get();
    }

    @Test
    void noBackoffCountsExactly() throws InterruptedException {
        assertEquals(EXPECTED_TOTAL, runContended(streak -> Thread.onSpinWait()));
    }

    @Test
    void fixedBackoffCountsExactly() throws InterruptedException {
        assertEquals(EXPECTED_TOTAL, runContended(streak -> LockSupport.parkNanos(1_000)));
    }

    @Test
    void exponentialJitterBackoffCountsExactly() throws InterruptedException {
        // Deterministic xorshift jitter, same shape as the evidence benchmark.
        assertEquals(EXPECTED_TOTAL, runContended(new Backoff() {
            private long state = 0x9E3779B97F4A7C15L;

            @Override
            public void afterFailure(int streak) {
                long cap = 1_000L << streak;
                state ^= state >>> 12;
                state ^= state << 25;
                state ^= state >>> 27;
                LockSupport.parkNanos(cap / 2 + Long.remainderUnsigned(state * 0x2545F4914F6CDD1DL, cap / 2));
            }
        }));
    }

    @Test
    void builtinAndManualCasCountExactly() throws InterruptedException {
        CasCounter counter = new CasCounter();
        Thread[] workers = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final boolean builtin = t % 2 == 0;
            workers[t] = new Thread(() -> {
                for (long i = 0; i < INCREMENTS_PER_THREAD; i++) {
                    if (builtin) counter.incrementViaBuiltin();
                    else counter.incrementManually();
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) worker.join();
        assertEquals(EXPECTED_TOTAL, counter.get());
    }

    @Test
    void singleWriterBaselineCountsExactlyWithOneThread() {
        SingleWriterCounter counter = new SingleWriterCounter();
        for (long i = 0; i < INCREMENTS_PER_THREAD; i++) counter.increment();
        assertEquals(INCREMENTS_PER_THREAD, counter.get());
    }

    @Test
    void evidenceBenchmarkParametersMatchTheRunnerContract() throws NoSuchFieldException {
        Field scenario = CasLinuxEvidenceBenchmark.class.getField("scenario");
        assertEquals(List.of("cas", "singleWriter"), List.of(scenario.getAnnotation(Param.class).value()));
        Field backoff = CasLinuxEvidenceBenchmark.class.getField("backoff");
        assertEquals(List.of("none", "fixed", "expjitter"), List.of(backoff.getAnnotation(Param.class).value()),
            "scripts/performance-lab/labs/cas-contention.conf selects these with -p backoff=<policy>");
    }

    @Test
    void affinityIsRefusedRatherThanFakedOffLinux() {
        if (!CpuAffinity.isSupported()) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> CpuAffinity.pinCurrentThread(0));
        }
    }
}
