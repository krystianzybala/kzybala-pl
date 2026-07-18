package pl.kzybala.lab.spscringbuffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openjdk.jmh.infra.Control;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression suite for the batch-20260717T150131Z hang: a full ring, a
 * consumer already parked on JMH's iteration latch, and a producer
 * spinning forever inside {@code produce()}. Every worker loop must be
 * independently able to return once {@link Control#stopMeasurement} is
 * set — regardless of what the other worker is doing.
 */
class SpscTerminationTest {

    private SpscLinuxEvidenceBenchmark bench(String cursorMode, int batch, int capacity) {
        SpscLinuxEvidenceBenchmark bench = new SpscLinuxEvidenceBenchmark();
        bench.cursorMode = cursorMode;
        bench.batch = batch;
        bench.capacity = capacity;
        // the setup only reads getGroupThreadCount() == 2
        bench.setUp(new org.openjdk.jmh.infra.ThreadParams(0, 2, 0, 2, 0, 1, 0, 2, 0, 1));
        return bench;
    }

    private static Control stopped() {
        Control control = new Control();
        control.stopMeasurement = true;
        return control;
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void producerReturnsWhenStoppedWhileRingIsFull() {
        SpscLinuxEvidenceBenchmark bench = bench("cached", 1, 8);
        SpscLinuxEvidenceBenchmark.ProducerCounters counters = new SpscLinuxEvidenceBenchmark.ProducerCounters();
        Control running = new Control();
        // fill the ring completely — the exact real-host precondition
        for (int i = 0; i < 8; i++) {
            assertTrue(bench.produce(running, null, counters));
        }
        // no consumer will ever drain: the stopped producer must return
        assertFalse(bench.produce(stopped(), null, counters),
            "a stopped producer facing a full ring must return, never spin");
        assertEquals(8, counters.producedItems);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void consumerReturnsWhenStoppedWhileRingIsEmpty() {
        SpscLinuxEvidenceBenchmark bench = bench("cached", 1, 8);
        SpscLinuxEvidenceBenchmark.ConsumerCounters counters = new SpscLinuxEvidenceBenchmark.ConsumerCounters();
        assertEquals(0, bench.consume(stopped(), null, counters),
            "a stopped consumer facing an empty ring must return, never spin");
        assertEquals(0, counters.consumedItems);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void eachWorkerStopsIndependentlyOfTheOther() throws Exception {
        // producer thread against a full ring, consumer never running:
        SpscLinuxEvidenceBenchmark bench = bench("uncached", 1, 8);
        SpscLinuxEvidenceBenchmark.ProducerCounters pc = new SpscLinuxEvidenceBenchmark.ProducerCounters();
        Control control = new Control();
        for (int i = 0; i < 8; i++) bench.produce(control, null, pc);
        AtomicBoolean returned = new AtomicBoolean(false);
        Thread producer = new Thread(() -> {
            bench.produce(control, null, pc);
            returned.set(true);
        });
        producer.start();
        Thread.sleep(100); // producer is now spinning on the full ring
        assertFalse(returned.get());
        control.stopMeasurement = true; // JMH ends the iteration
        producer.join(5_000);
        assertTrue(returned.get(), "producer must terminate once stopMeasurement is set");

        // consumer thread against an empty ring, producer already gone:
        SpscLinuxEvidenceBenchmark bench2 = bench("cached", 64, 8);
        SpscLinuxEvidenceBenchmark.ConsumerCounters cc = new SpscLinuxEvidenceBenchmark.ConsumerCounters();
        Control control2 = new Control();
        AtomicBoolean returned2 = new AtomicBoolean(false);
        Thread consumer = new Thread(() -> {
            bench2.consume(control2, null, cc);
            returned2.set(true);
        });
        consumer.start();
        Thread.sleep(100);
        assertFalse(returned2.get());
        control2.stopMeasurement = true;
        consumer.join(5_000);
        assertTrue(returned2.get(), "consumer must terminate once stopMeasurement is set");
    }

    /**
     * The exact previously-hanging case plus the matrix corners, run as
     * real grouped JMH (in-process fork, minimal iterations) under a strict
     * timeout — full-ring and empty-ring shutdown paths are exercised on
     * every run, and the counters must stay internally consistent.
     */
    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void groupedJmhSmokeTerminatesAcrossTheMatrix() throws Exception {
        String[][] cases = {
            {"cached", "1", "1024"},   // the batch-20260717T150131Z hang
            {"uncached", "1", "1024"},
            {"cached", "64", "1024"},
            {"cached", "1", "65536"},
            {"cached", "64", "65536"},
        };
        for (String[] c : cases) {
            var results = new Runner(new OptionsBuilder()
                .include(SpscLinuxEvidenceBenchmark.class.getSimpleName())
                .param("cursorMode", c[0])
                .param("batch", c[1])
                .param("capacity", c[2])
                .forks(0)
                .warmupIterations(0)
                .measurementIterations(1)
                .measurementTime(TimeValue.milliseconds(100))
                .threads(2)
                .build()).run();
            var primary = results.iterator().next();
            var secondaries = primary.getSecondaryResults();
            double produced = secondaries.get("producedItems").getScore();
            double consumed = secondaries.get("consumedItems").getScore();
            long capacity = Long.parseLong(c[2]);
            assertTrue(produced > 0, "items must have been produced");
            assertTrue(consumed > 0, "items must have been consumed");
            assertTrue(produced - consumed >= 0 && produced - consumed <= capacity,
                "in-flight items at shutdown bounded by capacity (" + produced + " vs " + consumed + ")");
            assertEquals(0.0, secondaries.get("sequenceViolations").getScore(), 0.0);
            // invocation throughput is NOT item throughput: the counters are
            // the authoritative item rates and must exist independently
            assertTrue(secondaries.containsKey("producerFullRetries"));
            assertTrue(secondaries.containsKey("consumerEmptyPolls"));
        }
    }
}
