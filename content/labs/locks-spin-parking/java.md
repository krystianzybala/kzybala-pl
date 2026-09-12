# Locks, spin waiting and parking — Java

## Shared fixtures

```java
public final class LocksFixtures {
    public static final int WORKER_COUNT = 4;
    public static final int OPS_PER_WORKER = 2_000;
    public static final int LONG_CS_BUSY_ITERATIONS = 200;
    public static final int SPIN_LIMIT = 100;

    public static long busyWork(int iterations) { /* deterministic, side-effect-free */ }
}
```

Identical, byte-for-byte, to
`code/fixtures/locks-spin-parking-fixtures.json` and to the Rust crate's
own `fixtures` module (rust.md). The correctness oracle
(`LockResult.isCorrect`) checks the shared counter's exact final value
for every variant.

## Mutex variants (uncontended, short contended, long critical section)

```java
public final class MutexKernel {
    private static LockResult run(int workerCount, int opsPerWorker, int busyIterations) {
        ReentrantLock lock = new ReentrantLock();
        // each worker: lock(); [busyWork if busyIterations>0]; counter++; unlock();
    }
    public static LockResult uncontended() { return run(1, OPS_PER_WORKER, 0); }
    public static LockResult shortContended(int workers, int ops) { return run(workers, ops, 0); }
    public static LockResult longCriticalSection(int workers, int ops, int busy) { return run(workers, ops, busy); }
}
```

All three share one `run()` — they differ only in worker count and
critical-section length, isolating exactly the two variables this lab's
theory page is about.

## CAS loop

```java
public static LockResult run(int workerCount, int opsPerWorker) {
    AtomicLong counter = new AtomicLong(0);
    // each worker retries counter.compareAndSet(cur, cur + 1) until success,
    // counting every failed attempt
}
```

No lock at all — `CasLoopKernel` reports failed-CAS counts explicitly
(`LockResult.casFailures`), never hiding them inside a retry loop.

## Spin-then-park hybrid

`SpinThenParkKernel`'s inner `SpinParkLock` spins up to `SPIN_LIMIT`
times, then registers on a `ConcurrentLinkedQueue<Thread>` waiter list
and calls `LockSupport.park()` — with a double-check re-CAS after
registering, the standard missed-wakeup guard (if the lock freed up
between the last failed spin attempt and joining the waiter queue, take
it immediately rather than parking with no one left to wake the waiter).
`unlock()` pops one waiter and calls `LockSupport.unpark()` on it. This
is a teaching implementation: the waiter queue does not guarantee strict
FIFO wakeup order, and an `unpark` occasionally targets a thread that
already re-acquired the lock via the double-check path — harmless,
since `park()`/`unpark()` permits are designed to tolerate exactly this
(a stray permit is simply consumed by that thread's next `park()` call
without blocking).

## Correctness tests

`LocksOperationsTest` asserts, for every variant: the shared counter
reaches exactly `workerCount × opsPerWorker`. A dedicated test runs the
spin-then-park variant with `spinLimit=0` to exercise the park path under
guaranteed contention — its resulting park count is reported, not
asserted, because whether a park actually occurs (versus the double-check
re-CAS winning first) is a genuine timing race, not something a test can
force deterministically. Run with:

```sh
cd content/labs/locks-spin-parking/code/java && mvn test
```

## JMH benchmark

`LocksBenchmark` runs each variant as its own `@Benchmark` method; every
kernel spawns and joins its own worker threads internally, so every
measured operation is a macro measurement of the full worker set running
to completion (see benchmark.md's operation definition).

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/locks-spin-parking/code/java" rel="noopener"><code>content/labs/locks-spin-parking/code/java/</code></a>
in this site's repository.
