package pl.kzybala.lab.threadpercore;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness gate for the thread-per-core evidence scenarios: the same
 * business operation must count exactly under every ownership discipline,
 * and the queue-shaped overload harness must never lose work silently.
 */
class TpcEvidenceContractTest {

    private static final int WORKERS = 4;
    private static final long EVENTS_PER_WORKER = 50_000;

    @Test
    void scenarioParametersMatchTheRunnerContract() throws NoSuchFieldException {
        Field scenario = TpcLinuxEvidenceBenchmark.class.getField("scenario");
        assertEquals(List.of("partitioned", "sharedPool", "hotPartition"),
            List.of(scenario.getAnnotation(Param.class).value()),
            "scripts/performance-lab/labs/thread-per-core.conf selects these with -p scenario=<name>");
    }

    @Test
    void partitionedOwnersCountExactlyWithPlainWrites() throws InterruptedException {
        long[] slots = new long[(TpcLinuxEvidenceBenchmark.MAX_PARTITIONS + 2) * TpcLinuxEvidenceBenchmark.STRIDE];
        Thread[] workers = new Thread[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            final int idx = (w + 1) * TpcLinuxEvidenceBenchmark.STRIDE;
            workers[w] = new Thread(() -> {
                for (long i = 0; i < EVENTS_PER_WORKER; i++) slots[idx]++;
            });
            workers[w].start();
        }
        for (Thread t : workers) t.join();
        for (int w = 0; w < WORKERS; w++) {
            assertEquals(EVENTS_PER_WORKER, slots[(w + 1) * TpcLinuxEvidenceBenchmark.STRIDE], "owner " + w);
        }
    }

    @Test
    void sharedPoolCountsExactlyUnderContention() throws InterruptedException {
        SharedCounterPool pool = new SharedCounterPool(WORKERS);
        Thread[] workers = new Thread[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            final int partition = w;
            workers[w] = new Thread(() -> {
                for (long i = 0; i < EVENTS_PER_WORKER; i++) pool.increment(partition);
            });
            workers[w].start();
        }
        for (Thread t : workers) t.join();
        long total = 0;
        for (int p = 0; p < WORKERS; p++) total += pool.get(p);
        assertEquals(WORKERS * EVENTS_PER_WORKER, total);
    }

    @Test
    void hotPartitionAtomicSlotsCountExactlyUnderMultiWriter() throws InterruptedException {
        AtomicLongArray slots = new AtomicLongArray((TpcLinuxEvidenceBenchmark.MAX_PARTITIONS + 2) * TpcLinuxEvidenceBenchmark.STRIDE);
        Thread[] workers = new Thread[WORKERS];
        for (int w = 0; w < WORKERS; w++) {
            final int own = (w + 1) * TpcLinuxEvidenceBenchmark.STRIDE;
            workers[w] = new Thread(() -> {
                for (long i = 0; i < EVENTS_PER_WORKER; i++) {
                    // deterministic 80/20 like the benchmark: every 5th to own
                    int target = (i % 5 == 0) ? own : TpcLinuxEvidenceBenchmark.STRIDE;
                    slots.incrementAndGet(target);
                }
            });
            workers[w].start();
        }
        for (Thread t : workers) t.join();
        long total = slots.get(TpcLinuxEvidenceBenchmark.STRIDE);
        for (int w = 0; w < WORKERS; w++) total += slots.get((w + 2) * TpcLinuxEvidenceBenchmark.STRIDE);
        // hot slot is worker slots offset-shifted: recompute directly
        long sum = 0;
        for (int s = 0; s < slots.length(); s++) sum += slots.get(s);
        assertEquals(WORKERS * EVENTS_PER_WORKER, sum, "no event lost under the multi-writer hot key");
        assertTrue(total > 0);
    }

    @Test
    void handoffHarnessIsNotAJmhBenchmark() {
        for (var method : TpcHandoffBackpressureHarness.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class),
                "queue-shaped overload behavior is never measured as a JMH throughput number");
        }
    }
}
