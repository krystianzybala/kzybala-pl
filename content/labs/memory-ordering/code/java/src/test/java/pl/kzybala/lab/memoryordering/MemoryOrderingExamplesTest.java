package pl.kzybala.lab.memoryordering;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These are correctness tests, not benchmarks — this lab deliberately does
 * not present a synthetic "memory ordering benchmark" (see theory.md
 * "Methodology and limitations"). {@link PlainPublication} and the
 * store-buffering litmus test are inherently racy by design — this class
 * does not assert their racy outcomes, only that the release/acquire fix
 * and the atomic counter behave correctly, which is the guarantee each is
 * actually supposed to provide.
 */
class MemoryOrderingExamplesTest {

    @Test
    void releaseAcquirePublication_consumerReliablyObservesPublicationWithinBoundedSpins() throws InterruptedException {
        // tryConsume() can only ever return true once it has actually seen
        // data == 42 (it checks this explicitly), so the property worth
        // testing isn't "never returns true incorrectly" — it's "does the
        // release/acquire pair reliably deliver visibility promptly," i.e.
        // does the consumer's bounded spin-wait actually succeed, every
        // trial. Contrast with PlainPublication, which this test suite
        // deliberately does NOT make the same claim about (see below).
        for (int trial = 0; trial < 2_000; trial++) {
            ReleaseAcquirePublication publication = new ReleaseAcquirePublication();
            boolean[] consumed = { false };

            Thread publisher = new Thread(publication::publish);
            Thread consumer = new Thread(() -> {
                for (int i = 0; i < 1_000_000 && !consumed[0]; i++) {
                    if (publication.tryConsume()) consumed[0] = true;
                }
            });

            publisher.start();
            consumer.start();
            publisher.join();
            consumer.join();

            assertTrue(consumed[0], "consumer failed to observe the publication within its spin bound on trial " + trial);
        }
    }

    @Test
    void plainPublication_publisherAndDataAreConsistentWhenObservedSynchronously() {
        // A same-thread sanity check only — PlainPublication's whole point
        // is that cross-thread ordering is NOT guaranteed, so this test
        // does not attempt to assert anything about concurrent visibility.
        PlainPublication publication = new PlainPublication();
        publication.publish();
        assertTrue(publication.tryConsume());
    }

    @Test
    void volatileCounter_isCorrectUnderConcurrentIncrement() throws InterruptedException {
        VolatileCounter counter = new VolatileCounter();
        int iterations = 100_000;

        Thread t1 = new Thread(() -> { for (int i = 0; i < iterations; i++) counter.incrementAtomically(); });
        Thread t2 = new Thread(() -> { for (int i = 0; i < iterations; i++) counter.incrementAtomically(); });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(2 * iterations, counter.get());
    }

    @Test
    void storeBufferingLitmusTest_runsAndReportsWithoutFailingOnEitherOutcome() throws InterruptedException {
        // This is an investigation tool, not a pass/fail gate on a racy
        // hardware phenomenon (see theory.md "Methodology and limitations",
        // and the lab's investigation task). It only asserts that both
        // observed values are one of the two legal results (0 or 1).
        int sawBothZeroUnderOpaque = 0;
        for (int i = 0; i < 500; i++) {
            int[] result = StoreBufferingLitmusTest.runOnce(StoreBufferingLitmusTest.Mode.OPAQUE);
            assertTrue(result[0] == 0 || result[0] == 1);
            assertTrue(result[1] == 0 || result[1] == 1);
            if (result[0] == 0 && result[1] == 0) sawBothZeroUnderOpaque++;
        }
        System.out.printf(
            "StoreBufferingLitmusTest (OPAQUE): both-saw-0 in %d/500 runs on this machine/JVM.%n",
            sawBothZeroUnderOpaque);
    }
}
