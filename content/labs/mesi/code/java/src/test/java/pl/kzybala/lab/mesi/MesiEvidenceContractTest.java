package pl.kzybala.lab.mesi;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness gate for the measured coherence scenarios (the educational
 * MESI simulator lives in the page's interactive model and is NEVER
 * treated as hardware evidence — this suite covers the measured side
 * only). Exact-count semantics under real concurrent threads are the
 * oracle for every writing scenario.
 */
class MesiEvidenceContractTest {

    private static final int THREADS = 2;
    private static final long INCREMENTS = 100_000;

    @Test
    void scenarioParametersMatchTheRunnerContract() throws NoSuchFieldException {
        Field scenario = MesiLinuxEvidenceBenchmark.class.getField("scenario");
        assertEquals(
            List.of("singleWriter", "sharedReaders", "writerInvalidation", "pingPong", "paddedLines"),
            List.of(scenario.getAnnotation(Param.class).value()),
            "scripts/performance-lab/labs/mesi.conf selects these with -p scenario=<name>");
    }

    @Test
    void slotsAreAtLeastALineApart() {
        assertTrue((MesiLinuxEvidenceBenchmark.SLOT_Y - MesiLinuxEvidenceBenchmark.SLOT_X) * 8 >= 128,
            "the padded control slots must be separated by well over any plausible line size");
    }

    @Test
    void pingPongTwoWritersCountExactly() throws InterruptedException {
        AtomicLongArray slots = new AtomicLongArray(64);
        Thread[] workers = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            workers[t] = new Thread(() -> {
                for (long i = 0; i < INCREMENTS; i++) slots.incrementAndGet(MesiLinuxEvidenceBenchmark.SLOT_X);
            });
            workers[t].start();
        }
        for (Thread worker : workers) worker.join();
        assertEquals(THREADS * INCREMENTS, slots.get(MesiLinuxEvidenceBenchmark.SLOT_X));
    }

    @Test
    void paddedLinesWritersCountExactlyAndIndependently() throws InterruptedException {
        AtomicLongArray slots = new AtomicLongArray(64);
        Thread a = new Thread(() -> {
            for (long i = 0; i < INCREMENTS; i++) slots.incrementAndGet(MesiLinuxEvidenceBenchmark.SLOT_X);
        });
        Thread b = new Thread(() -> {
            for (long i = 0; i < INCREMENTS; i++) slots.incrementAndGet(MesiLinuxEvidenceBenchmark.SLOT_Y);
        });
        a.start(); b.start(); a.join(); b.join();
        assertEquals(INCREMENTS, slots.get(MesiLinuxEvidenceBenchmark.SLOT_X));
        assertEquals(INCREMENTS, slots.get(MesiLinuxEvidenceBenchmark.SLOT_Y));
    }

    @Test
    void readersAlwaysObserveAPublishedValue() throws InterruptedException {
        AtomicLongArray slots = new AtomicLongArray(64);
        slots.set(MesiLinuxEvidenceBenchmark.SLOT_X, 1);
        Thread writer = new Thread(() -> {
            for (long i = 0; i < INCREMENTS; i++) slots.incrementAndGet(MesiLinuxEvidenceBenchmark.SLOT_X);
        });
        final boolean[] sawUnpublished = {false};
        Thread reader = new Thread(() -> {
            for (long i = 0; i < INCREMENTS; i++) {
                if (slots.get(MesiLinuxEvidenceBenchmark.SLOT_X) < 1) sawUnpublished[0] = true;
            }
        });
        writer.start(); reader.start(); writer.join(); reader.join();
        assertTrue(!sawUnpublished[0], "a reader must never observe a pre-publication value");
        assertEquals(1 + INCREMENTS, slots.get(MesiLinuxEvidenceBenchmark.SLOT_X));
    }
}
