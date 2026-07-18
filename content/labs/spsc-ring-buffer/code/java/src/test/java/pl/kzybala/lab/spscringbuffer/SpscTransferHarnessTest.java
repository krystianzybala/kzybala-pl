package pl.kzybala.lab.spscringbuffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The finite harness must transfer exactly N items in order — and must
 * never hang: deadline expiry or a failure on either side stops both
 * workers within a bounded time.
 */
class SpscTransferHarnessTest {

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void transfersExactlyNItemsAcrossTheMatrix() throws InterruptedException {
        int[][] cases = {{1024, 1}, {1024, 64}, {128, 64}, {65536, 1}};
        for (boolean cached : new boolean[] {true, false}) {
            for (int[] c : cases) {
                var result = SpscTransferHarness.runTransfer(new SpscTransferHarness.Config(
                    500_000, c[0], cached, c[1], TimeUnit.SECONDS.toNanos(20)));
                assertTrue(result.completed(), "cached=" + cached + " capacity=" + c[0] + " batch=" + c[1] + ": " + result.failure());
                assertEquals(500_000, result.produced());
                assertEquals(500_000, result.consumed());
                assertNull(result.failure());
                assertTrue(result.itemsPerSecond() > 0);
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void deadlineExpiryStopsBothWorkersWithoutHanging() throws InterruptedException {
        // items chosen far beyond what ~50ms can transfer
        var result = SpscTransferHarness.runTransfer(new SpscTransferHarness.Config(
            Long.MAX_VALUE / 4, 1024, true, 1, TimeUnit.MILLISECONDS.toNanos(50)));
        assertFalse(result.completed());
        assertTrue(result.deadlineExpired(), "deadline expiry must be reported");
        assertTrue(result.produced() > 0, "workers ran before the deadline");
        // both workers returned — the @Timeout above proves neither hung
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aFailureOnOneSideCancelsTheOtherWithoutHanging() throws InterruptedException {
        // injected consumer exception mid-transfer: the shared cancel flag
        // must stop the producer (which would otherwise keep publishing
        // millions of items or spin on a full ring forever)
        var result = SpscTransferHarness.runTransfer(new SpscTransferHarness.Config(
            50_000_000, 1024, true, 1, TimeUnit.SECONDS.toNanos(20)), 10_000);
        assertFalse(result.completed());
        assertTrue(result.cancelled(), "the shared cancellation flag must be set");
        assertTrue(String.valueOf(result.failure()).contains("injected consumer fault"));
        assertTrue(result.produced() < 50_000_000, "producer stopped early instead of publishing everything");
        // the @Timeout above proves neither worker hung after the failure
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tinyRingWithBatchingStaysExact() throws InterruptedException {
        var result = SpscTransferHarness.runTransfer(new SpscTransferHarness.Config(
            10_000_000, 8, true, 8, TimeUnit.SECONDS.toNanos(20)));
        assertTrue(result.completed(), String.valueOf(result.failure()));
        assertEquals(10_000_000, result.consumed());
    }
}
