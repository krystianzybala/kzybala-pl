package pl.kzybala.lab.spscringbuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpscRingBufferTest {

    @Test
    void tryProduce_rejectsWhenFull() {
        SpscRingBuffer buffer = new SpscRingBuffer(2);
        assertTrue(buffer.tryProduce(1));
        assertTrue(buffer.tryProduce(2));
        assertFalse(buffer.tryProduce(3), "reservation should be rejected once the buffer is full");
    }

    @Test
    void tryConsume_reportsEmptyBeforeAnythingIsPublished() {
        SpscRingBuffer buffer = new SpscRingBuffer(2);
        long[] out = new long[1];
        assertFalse(buffer.tryConsume(out));
    }

    @Test
    void producedValuesAreConsumedInFifoOrderAcrossWrapAround() {
        SpscRingBuffer buffer = new SpscRingBuffer(2);
        long[] out = new long[1];

        assertTrue(buffer.tryProduce(10));
        assertTrue(buffer.tryProduce(20));
        assertTrue(buffer.tryConsume(out));
        assertEquals(10, out[0]);
        assertTrue(buffer.tryConsume(out));
        assertEquals(20, out[0]);

        // Buffer is now empty; produce two more, which wrap the slot index.
        assertTrue(buffer.tryProduce(30));
        assertTrue(buffer.tryProduce(40));
        assertTrue(buffer.tryConsume(out));
        assertEquals(30, out[0]);
        assertTrue(buffer.tryConsume(out));
        assertEquals(40, out[0]);
    }

    @Test
    void isCorrectAcrossRealProducerAndConsumerThreads() throws InterruptedException {
        SpscRingBuffer buffer = new SpscRingBuffer(1024);
        int items = 200_000;
        long[] received = new long[1];

        Thread producer = new Thread(() -> {
            for (long i = 0; i < items; i++) {
                while (!buffer.tryProduce(i)) Thread.onSpinWait();
            }
        });
        Thread consumer = new Thread(() -> {
            long[] out = new long[1];
            long expected = 0;
            long sum = 0;
            for (int i = 0; i < items; i++) {
                while (!buffer.tryConsume(out)) Thread.onSpinWait();
                sum += (out[0] == expected) ? 1 : 0;
                expected++;
            }
            received[0] = sum;
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        assertEquals(items, received[0], "every value must be received exactly once, in FIFO order");
    }

    @Test
    void constructorRejectsNonPowerOfTwoCapacity() {
        try {
            new SpscRingBuffer(3);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // capacity must be a power of two — see class Javadoc.
        }
    }
}
