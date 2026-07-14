package pl.kzybala.lab.threadpercore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThreadPerCoreTest {

    @Test
    void partitionedCounter_isCorrectOnItsOneOwningThread() {
        PartitionedCounter counter = new PartitionedCounter();
        for (int i = 0; i < 10_000; i++) counter.increment();
        assertEquals(10_000, counter.get());
    }

    @Test
    void sharedCounterPool_isCorrectUnderConcurrentIncrementAcrossPartitions() throws InterruptedException {
        int partitions = 4;
        int incrementsPerThread = 20_000;
        SharedCounterPool pool = new SharedCounterPool(partitions);

        Thread[] threads = new Thread[partitions];
        for (int t = 0; t < partitions; t++) {
            int partition = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < incrementsPerThread; i++) pool.increment(partition);
            });
        }
        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();

        for (int p = 0; p < partitions; p++) {
            assertEquals(incrementsPerThread, pool.get(p), "partition " + p + " should only reflect its own thread's increments");
        }
    }

    @Test
    void ownedPartitions_eachThreadOnlyEverSeesItsOwnCounter() throws InterruptedException {
        int coreCount = 4;
        int incrementsPerThread = 20_000;
        PartitionedCounter[] owned = new PartitionedCounter[coreCount];
        for (int i = 0; i < coreCount; i++) owned[i] = new PartitionedCounter();

        Thread[] threads = new Thread[coreCount];
        for (int t = 0; t < coreCount; t++) {
            PartitionedCounter counter = owned[t];
            threads[t] = new Thread(() -> {
                for (int i = 0; i < incrementsPerThread; i++) counter.increment();
            });
        }
        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();

        for (PartitionedCounter counter : owned) {
            assertEquals(incrementsPerThread, counter.get());
        }
    }
}
