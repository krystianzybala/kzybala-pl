# Virtual Threads vs Platform Threads vs Event Loops — Java

## Shared work primitives

```java
public static long cpuChecksum(int id, int iterations) {
    long x = id * SEED_MULTIPLIER + 1L; // relies on Java's silent 64-bit wraparound
    for (int i = 0; i < iterations; i++) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
    }
    return x;
}

public static void blockingWait(long nanos) {
    if (nanos > 0) {
        LockSupport.parkNanos(nanos);
    }
}
```

`cpuChecksum` is a pure xorshift64 function of `(id, iterations)` — its
result never depends on which thread, executor or scheduling model ran it.
This is what lets the correctness fixture assert one expected total
regardless of which of the five variants below produced it.
`blockingWait` uses `LockSupport.parkNanos` rather than `Thread.sleep`
specifically because it is the primitive virtual threads are built to
recognize and unmount around.

## Five variants, one task list

```java
public interface VariantRunner {
    RunResult run(List<TaskSpec> tasks) throws Exception;
}
```

**Platform-per-request** — one `new Thread` per task, joined at the end.
Simple, correct, and the baseline every other variant is compared against
for blocking-heavy datasets.

**Virtual-per-task** — the same per-task shape, but via
`Executors.newVirtualThreadPerTaskExecutor()`:

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<?>> futures = tasks.stream()
            .<Future<?>>map(t -> executor.submit(() -> runOne(t, sum, completed)))
            .toList();
    for (Future<?> f : futures) f.get();
}
```

`ExecutorService` is `AutoCloseable` since JDK 19 — `close()` blocks until
every submitted task completes, so the try-with-resources block is itself
the join point.

**Fixed event loop** — a single-thread `ScheduledExecutorService`. The
blocking-wait component is registered as a **non-blocking timer**, never a
park on the loop thread; the CPU-stage checksum always executes on that one
loop thread when its timer fires:

```java
ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
for (TaskSpec t : tasks) {
    loop.schedule(() -> { /* checksum + bookkeeping */ }, t.ioWaitNanos(), TimeUnit.NANOSECONDS);
}
```

This is the exact mechanism the theory page describes: the loop thread is
never occupied "waiting," but it is still the only thread that ever runs
application code, so a long CPU stage still serializes behind it.

**CPU-bound pool** — a `Executors.newFixedThreadPool(availableProcessors())`,
sized to cores, for datasets whose bottleneck is CPU rather than blocking
fan-out.

**Mixed** — virtual threads absorb the blocking-wait fan-out; each task then
hands its CPU stage to a shared, core-sized fixed pool via a nested
`Future`, isolating the scarce resource (cores) from the cheap one (blocked
waiters):

```java
Future<Long> cpuFuture = cpuPool.submit(() -> Work.cpuChecksum(t.id(), t.cpuIterations()));
sum.addAndGet(cpuFuture.get());
```

## The lock/native pinning dataset

```java
private static final Object PIN_MONITOR = new Object();

public static long runLocked(TaskSpec task) {
    synchronized (PIN_MONITOR) {
        blockingWait(task.ioWaitNanos());
        return cpuChecksum(task.id(), task.cpuIterations());
    }
}
```

Every task using `lockNativePinningCase` serializes through one shared
monitor, held across the simulated blocking wait — the classic shape that
used to pin a virtual thread to its carrier for the whole blocking
duration. As of JDK 24 (JEP 491), ordinary `synchronized` blocks no longer
pin in this way on most blocking operations; this repository's JDK 26
toolchain therefore does not reproduce the historical carrier-starvation
penalty for this exact code shape. The dataset is retained because the
mechanism it teaches (a virtual thread that cannot unmount degrades to
platform-thread behavior) remains real for other pinning causes (certain
native/JNI calls); see theory.md Limitations and benchmark.md for the
honest, non-fabricated account of what this JDK can and cannot demonstrate
here.

## JMH benchmark

`VpeBenchmark` (`@Fork(1)`, 3 warmup + 5 measurement iterations of 1s each,
`Mode.AverageTime`) runs each variant against the dataset its mechanism is
meant to expose — platform/virtual/event-loop/mixed against
`simulatedSocketWait`, the CPU-bound pool against `shortCpuStage`, and the
event loop separately against `longCpuStage` to show the stall.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/virtual-platform-event-loop/code/java" rel="noopener"><code>content/labs/virtual-platform-event-loop/code/java/</code></a>
in this site's repository.
