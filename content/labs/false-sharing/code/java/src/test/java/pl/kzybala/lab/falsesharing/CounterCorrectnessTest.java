package pl.kzybala.lab.falsesharing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Deterministic correctness gate for every benchmarked variant — run before
 * any benchmark number is accepted (docs/benchmark-correctness-fixtures.md).
 *
 * <p>The constants below are the shared fixture
 * {@code ../fixtures/false-sharing-fixtures.json}; the Rust test block in
 * {@code code/rust/src/lib.rs} hard-codes exactly the same cases. If you
 * change a value, change all three files in the same commit.
 */
class CounterCorrectnessTest {

    // Fixture: two-writers-dedicated-counters
    private static final int TWO_WRITER_THREADS = 2;
    private static final long INCREMENTS_PER_THREAD = 100_000;
    private static final long EXPECTED_PER_COUNTER = 100_000;

    // Fixture: four-owners-sharded-reduction
    private static final int SHARD_OWNERS = 4;
    private static final long INCREMENTS_PER_OWNER = 25_000;
    private static final long EXPECTED_PER_SHARD = 25_000;
    private static final long EXPECTED_SHARDED_TOTAL = 100_000;

    private static void runJoined(Runnable a, Runnable b) throws InterruptedException {
        Thread t1 = new Thread(a);
        Thread t2 = new Thread(b);
        t1.start();
        t2.start();
        t1.join();
        t2.join();
    }

    @Test
    void sharedCountersCountExactly() throws InterruptedException {
        SharedCounters c = new SharedCounters();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS_PER_THREAD; i++) c.counterA++; },
            () -> { for (long i = 0; i < INCREMENTS_PER_THREAD; i++) c.counterB++; });
        assertEquals(EXPECTED_PER_COUNTER, c.counterA);
        assertEquals(EXPECTED_PER_COUNTER, c.counterB);
    }

    @Test
    void paddedCountersCountExactly() throws InterruptedException {
        PaddedCounters c = new PaddedCounters();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS_PER_THREAD; i++) c.counterA++; },
            () -> { for (long i = 0; i < INCREMENTS_PER_THREAD; i++) c.counterB++; });
        assertEquals(EXPECTED_PER_COUNTER, c.counterA);
        assertEquals(EXPECTED_PER_COUNTER, c.counterB);
    }

    @Test
    void contendedCountersCountExactly() throws InterruptedException {
        ContendedCounters c = new ContendedCounters();
        runJoined(
            () -> { for (long i = 0; i < INCREMENTS_PER_THREAD; i++) c.counterA++; },
            () -> { for (long i = 0; i < INCREMENTS_PER_THREAD; i++) c.counterB++; });
        assertEquals(EXPECTED_PER_COUNTER, c.counterA);
        assertEquals(EXPECTED_PER_COUNTER, c.counterB);
    }

    @Test
    void shardedCountersReduceExactlyAfterJoin() throws InterruptedException {
        ShardedCounters c = new ShardedCounters(SHARD_OWNERS);
        Thread[] owners = new Thread[SHARD_OWNERS];
        for (int s = 0; s < SHARD_OWNERS; s++) {
            final int shard = s;
            owners[s] = new Thread(() -> {
                for (long i = 0; i < INCREMENTS_PER_OWNER; i++) c.add(shard, 1);
            });
            owners[s].start();
        }
        for (Thread owner : owners) owner.join();
        for (int s = 0; s < SHARD_OWNERS; s++) {
            assertEquals(EXPECTED_PER_SHARD, c.shardValue(s), "shard " + s);
        }
        assertEquals(EXPECTED_SHARDED_TOTAL, c.total());
    }

    @Test
    void shardedCountersRejectInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ShardedCounters(0));
        ShardedCounters c = new ShardedCounters(2);
        assertThrows(IndexOutOfBoundsException.class, () -> c.add(2, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> c.shardValue(-1));
    }

    // Single-threaded determinism: the counter semantics themselves are
    // sequential per counter (one dedicated writer each); interleaving with
    // another writer on the *other* counter must never change the result.
    @Test
    void countersAreIndependentUnderSingleThread() {
        SharedCounters c = new SharedCounters();
        c.counterA += 3;
        c.counterB += 5;
        assertEquals(3, c.counterA);
        assertEquals(5, c.counterB);
    }
}
