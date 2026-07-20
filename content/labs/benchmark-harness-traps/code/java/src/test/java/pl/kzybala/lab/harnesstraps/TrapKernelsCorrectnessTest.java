package pl.kzybala.lab.harnesstraps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared-fixture correctness gate
 * (code/fixtures/benchmark-harness-traps-fixtures.json): the values below
 * are hard-coded identically in the Rust suite. Every benchmark variant —
 * trap and corrected alike — must compute these exact results before any
 * timing is trusted; a variant that computes something different is a
 * broken benchmark, not a faster one.
 */
class TrapKernelsCorrectnessTest {

    // fixture: cases.scalar
    static final long SCALAR_MIXED = 2260733264014075113L;
    // fixture: cases.reduction
    static final long REDUCTION_SUM = 6622022393378204083L;
    // fixture: cases.parser
    static final long PARSER_CHECKSUM = 1274698891203359752L;
    static final int PARSER_INPUT_LENGTH = 1760;
    static final String PARSER_INPUT_PREFIX = "888327,51652,763743,795107,470850,165125";
    // fixture: cases.counter
    static final long COUNTER_FINAL = 206428032307178832L;

    @Test
    void scalarMixMatchesTheSharedFixture() {
        assertEquals(SCALAR_MIXED, TrapKernels.mixScalar(42L, 1000));
    }

    @Test
    void arrayReductionMatchesTheSharedFixture() {
        long[] data = TrapKernels.fillArray(42L, 4096);
        assertEquals(4096, data.length);
        assertEquals(REDUCTION_SUM, TrapKernels.reduce(data));
    }

    @Test
    void parserInputAndChecksumMatchTheSharedFixture() {
        String input = TrapKernels.buildParserInput(7L, 256);
        assertEquals(PARSER_INPUT_LENGTH, input.length());
        assertTrue(input.startsWith(PARSER_INPUT_PREFIX), input.substring(0, 40));
        assertEquals(PARSER_CHECKSUM, TrapKernels.parseChecksum(input, 256));
    }

    @Test
    void parserRejectsAWrongValueCount() {
        String input = TrapKernels.buildParserInput(7L, 256);
        assertThrows(IllegalStateException.class,
            () -> TrapKernels.parseChecksum(input, 255));
    }

    @Test
    void statefulCounterMatchesTheSharedFixture() {
        assertEquals(COUNTER_FINAL, TrapKernels.counterAfter(0L, 10000));
    }

    @Test
    void statefulCounterResetPreventsStateLeakageBetweenRuns() {
        TrapKernels.StatefulCounter counter = new TrapKernels.StatefulCounter(0L);
        long first = 0;
        for (int i = 0; i < 10000; i++) {
            first = counter.advance();
        }
        // the LEAK: running again without reset continues from mutated state
        long leaked = 0;
        for (int i = 0; i < 10000; i++) {
            leaked = counter.advance();
        }
        // the CORRECTED form: reset restores the fixture result exactly
        counter.reset(0L);
        long resetRun = 0;
        for (int i = 0; i < 10000; i++) {
            resetRun = counter.advance();
        }
        assertEquals(COUNTER_FINAL, first);
        assertEquals(COUNTER_FINAL, resetRun);
        assertTrue(leaked != first, "a leaked-state run must diverge from the fixture");
    }

    /**
     * Every measurement shape computes the same result — the harness is the
     * only thing that differs between trap and corrected variants.
     */
    @Test
    void everyBenchmarkVariantComputesTheFixtureResult() {
        for (String dataset : new String[] {"scalar", "reduction", "parser", "counter"}) {
            HarnessTrapsBenchmark bench = new HarnessTrapsBenchmark();
            bench.dataset = dataset;
            bench.setUp();
            long expected = switch (dataset) {
                case "scalar" -> SCALAR_MIXED;
                case "reduction" -> REDUCTION_SUM;
                case "parser" -> PARSER_CHECKSUM;
                default -> COUNTER_FINAL;
            };
            assertEquals(expected, bench.foldedInput(), dataset + "/foldedInput");
            assertEquals(expected, bench.runtimeInput(), dataset + "/runtimeInput");
            assertEquals(expected, bench.returnedResult(), dataset + "/returnedResult");
            assertEquals(expected, bench.setupInsideTimed(), dataset + "/setupInsideTimed");
            assertEquals(expected, bench.setupOutside(), dataset + "/setupOutside");
            // consumedResult routes through the same dispatch — verified via
            // returnedResult; invoking it needs a JMH-provided Blackhole.
        }
    }
}
