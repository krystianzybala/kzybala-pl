package pl.kzybala.lab.spscringbuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness for the evidence-scenario paths the base suite doesn't cover:
 * uncached cursor mode and batched consumption — item count, sequence,
 * full/empty behavior and wrap-around must hold in every scenario the
 * runner measures, before any timing is trusted.
 */
class SpscEvidenceContractTest {

    @Test
    void uncachedCursorModeTransfersExactlyAndInOrder() throws InterruptedException {
        SpscRingBuffer buffer = new SpscRingBuffer(1024, false);
        final long items = 200_000;
        Thread producer = new Thread(() -> {
            for (long i = 0; i < items; i++) {
                while (!buffer.tryProduce(i)) Thread.onSpinWait();
            }
        });
        final long[] consumed = {0};
        final boolean[] orderViolated = {false};
        Thread consumer = new Thread(() -> {
            long[] out = new long[1];
            long expected = 0;
            while (expected < items) {
                if (buffer.tryConsume(out)) {
                    if (out[0] != expected) orderViolated[0] = true;
                    expected++;
                } else {
                    Thread.onSpinWait();
                }
            }
            consumed[0] = expected;
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertEquals(items, consumed[0]);
        assertFalse(orderViolated[0], "uncached mode must preserve exact FIFO order");
    }

    @Test
    void batchDrainPreservesCountOrderAndWrapAround() throws InterruptedException {
        // Small capacity forces many wrap-arounds under a 64-item batch drain.
        SpscRingBuffer buffer = new SpscRingBuffer(128, true);
        final long items = 300_000;
        Thread producer = new Thread(() -> {
            for (long i = 0; i < items; i++) {
                while (!buffer.tryProduce(i)) Thread.onSpinWait();
            }
        });
        final long[] consumed = {0};
        final boolean[] orderViolated = {false};
        Thread consumer = new Thread(() -> {
            long[] out = new long[64];
            long expected = 0;
            while (expected < items) {
                int n = buffer.tryConsumeBatch(out, 64);
                if (n == 0) {
                    Thread.onSpinWait();
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    if (out[i] != expected + i) orderViolated[0] = true;
                }
                expected += n;
            }
            consumed[0] = expected;
        });
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        assertEquals(items, consumed[0], "every produced item consumed exactly once");
        assertFalse(orderViolated[0], "batch drain must preserve exact FIFO order across wrap-around");
    }

    @Test
    void batchDrainNeverReadsUnpublishedOrOverwritesUnacknowledged() {
        SpscRingBuffer buffer = new SpscRingBuffer(8, true);
        long[] out = new long[8];
        assertEquals(0, buffer.tryConsumeBatch(out, 8), "empty ring drains nothing");
        for (long i = 0; i < 8; i++) assertTrue(buffer.tryProduce(i));
        assertFalse(buffer.tryProduce(99), "full ring rejects rather than overwriting");
        assertEquals(8, buffer.tryConsumeBatch(out, 8));
        for (int i = 0; i < 8; i++) assertEquals(i, out[i]);
        assertEquals(0, buffer.tryConsumeBatch(out, 8), "drained ring is empty again");
        assertTrue(buffer.tryProduce(100), "acknowledged slots are reusable (wrap-around)");
    }
}
