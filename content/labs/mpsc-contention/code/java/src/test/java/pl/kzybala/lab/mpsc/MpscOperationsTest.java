package pl.kzybala.lab.mpsc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MpscOperationsTest {

    private interface Run {
        MpscResult run(int producers) throws InterruptedException;
    }

    private static void assertCorrectForAllProducerCounts(Run run) throws InterruptedException {
        for (int producerCount : MpscFixtures.PRODUCER_COUNTS) {
            MpscResult result = run.run(producerCount);
            assertTrue(
                    result.isCorrect(producerCount, MpscFixtures.ITEMS_PER_PRODUCER),
                    "producerCount=" + producerCount + " did not preserve per-producer FIFO order and exact counts"
            );
        }
    }

    @Test
    void sharedMpscPreservesPerProducerOrderAndExactCounts() throws InterruptedException {
        assertCorrectForAllProducerCounts(producers ->
                SharedMpscKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY));
    }

    @Test
    void batchedClaimPreservesPerProducerOrderAndExactCounts() throws InterruptedException {
        assertCorrectForAllProducerCounts(producers ->
                BatchedClaimKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY, MpscFixtures.BATCH_SIZE));
    }

    @Test
    void perProducerFanInPreservesPerProducerOrderAndExactCounts() throws InterruptedException {
        assertCorrectForAllProducerCounts(producers ->
                PerProducerFanInKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY));
    }

    @Test
    void mutexQueuePreservesPerProducerOrderAndExactCounts() throws InterruptedException {
        assertCorrectForAllProducerCounts(producers ->
                MutexQueueKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER, MpscFixtures.CAPACITY));
    }

    @Test
    void libraryQueuePreservesPerProducerOrderAndExactCounts() throws InterruptedException {
        assertCorrectForAllProducerCounts(producers ->
                LibraryQueueKernel.run(producers, MpscFixtures.ITEMS_PER_PRODUCER));
    }
}
