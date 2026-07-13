package pl.kzybala.lab.mesi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * These are correctness tests, not benchmarks — this lab deliberately does
 * not present a synthetic "MESI benchmark" as a portable performance
 * number (see theory.md "Diagnostic methodology"). They confirm both
 * examples behave correctly under the concurrency pattern each is meant to
 * illustrate, so a reader can trust the code before reasoning about the
 * coherence traffic it produces.
 */
class MesiExamplesTest {

    @Test
    void sharedWriterExample_isCorrectUnderConcurrentIncrement() throws InterruptedException {
        SharedWriterExample shared = new SharedWriterExample();
        int iterations = 100_000;

        Thread t1 = new Thread(() -> shared.incrementFrom(iterations));
        Thread t2 = new Thread(() -> shared.incrementFrom(iterations));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(2L * iterations, shared.value());
    }

    @Test
    void singleOwnerExample_isCorrectOnItsOneOwningThread() {
        SingleOwnerExample owner = new SingleOwnerExample();
        int iterations = 1_000;

        owner.addFrom(iterations);

        long expected = 0;
        for (int i = 0; i < iterations; i++) expected += i;
        assertEquals(expected, owner.total());
    }
}
