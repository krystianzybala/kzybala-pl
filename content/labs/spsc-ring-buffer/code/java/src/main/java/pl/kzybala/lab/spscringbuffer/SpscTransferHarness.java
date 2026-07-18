package pl.kzybala.lab.spscringbuffer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic finite transfer harness — the PRIMARY source for
 * end-to-end SPSC items-per-second (JMH remains the operation-cost view;
 * its invocation throughput is never published as item throughput).
 *
 * Protocol (docs/linux-evidence-runner.md):
 *   - exactly one producer and one consumer thread, pinned when
 *     {@code -Dplab.cpuA/-Dplab.cpuB} are set (placement verified);
 *   - common start barrier; the producer publishes exactly N sequential
 *     values, the consumer drains exactly N in order; both stop at N;
 *   - a shared cancellation/failure signal: any exception (or a sequence
 *     violation) on either side stops BOTH workers immediately;
 *   - a monotonic deadline stops both workers when it expires;
 *   - the main thread joins both with a bounded timeout and reports
 *     timeout diagnostics rather than waiting forever.
 *
 * Neither worker can spin forever if the other fails or exits early: every
 * retry loop re-checks the shared cancel flag and the deadline.
 */
public final class SpscTransferHarness {

    public record Config(long items, int capacity, boolean cachedCursors, int batch, long deadlineNanos) {}

    public record Result(
        boolean completed, boolean deadlineExpired, boolean cancelled,
        long produced, long consumed, long elapsedNanos, double itemsPerSecond,
        String failure) {}

    /** Runs one finite transfer; callable directly from tests. */
    public static Result runTransfer(Config config) throws InterruptedException {
        return runTransfer(config, -1);
    }

    /**
     * Package-private fault-injection variant: the consumer throws after
     * consuming {@code consumerFaultAfter} items — used by the regression
     * suite to prove a failure on one side cancels the other worker.
     */
    static Result runTransfer(Config config, long consumerFaultAfter) throws InterruptedException {
        var pair = new SpscRingBuffer(config.capacity(), config.cachedCursors());
        AtomicBoolean cancel = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>(null);
        CountDownLatch start = new CountDownLatch(1);
        long deadline = System.nanoTime() + config.deadlineNanos();
        final long items = config.items();

        long[] producedBox = new long[1];
        Thread producer = new Thread(() -> {
            try {
                WorkerPin pin = WorkerPin.pinningRequested() ? WorkerPin.establish("producer", WorkerPin.CPU_A) : null;
                start.await();
                long seq = 0;
                while (seq < items) {
                    if (cancel.get() || System.nanoTime() > deadline) break;
                    if (pair.tryProduce(seq)) {
                        seq++;
                    } else {
                        Thread.onSpinWait();
                    }
                }
                producedBox[0] = seq;
                if (pin != null) pin.verifyAndRecord();
            } catch (Throwable t) {
                failure.compareAndSet(null, "producer: " + t);
                cancel.set(true);
            }
        }, "spsc-producer");

        long[] consumedBox = new long[1];
        Thread consumer = new Thread(() -> {
            try {
                WorkerPin pin = WorkerPin.pinningRequested() ? WorkerPin.establish("consumer", WorkerPin.CPU_B) : null;
                long[] out = new long[config.batch()]; // preallocated once
                start.await();
                long expected = 0;
                while (expected < items) {
                    if (cancel.get() || System.nanoTime() > deadline) break;
                    int n = pair.tryConsumeBatch(out, config.batch());
                    if (n == 0) {
                        Thread.onSpinWait();
                        continue;
                    }
                    if (consumerFaultAfter >= 0 && expected + n > consumerFaultAfter) {
                        throw new IllegalStateException("injected consumer fault after " + expected + " items");
                    }
                    for (int i = 0; i < n; i++) {
                        if (out[i] != expected + i) {
                            failure.compareAndSet(null,
                                "consumer: sequence violation at " + (expected + i) + " (got " + out[i] + ")");
                            cancel.set(true);
                            return;
                        }
                    }
                    expected += n;
                }
                consumedBox[0] = expected;
                if (pin != null) pin.verifyAndRecord();
            } catch (Throwable t) {
                failure.compareAndSet(null, "consumer: " + t);
                cancel.set(true);
            }
        }, "spsc-consumer");

        producer.start();
        consumer.start();
        long begin = System.nanoTime();
        start.countDown();

        // bounded joins: deadline plus grace — the main thread never waits
        // forever on a wedged worker
        long joinBudgetMs = TimeUnit.NANOSECONDS.toMillis(config.deadlineNanos()) + 5_000;
        producer.join(joinBudgetMs);
        consumer.join(joinBudgetMs);
        boolean wedged = producer.isAlive() || consumer.isAlive();
        if (wedged) {
            cancel.set(true);
            failure.compareAndSet(null, "join timeout: producer alive=" + producer.isAlive()
                + ", consumer alive=" + consumer.isAlive() + " — dumping is left to the external timeout wrapper");
            producer.interrupt();
            consumer.interrupt();
            producer.join(2_000);
            consumer.join(2_000);
        }
        long elapsed = System.nanoTime() - begin;

        boolean completed = consumedBox[0] == items && failure.get() == null;
        boolean deadlineExpired = !completed && failure.get() == null && System.nanoTime() > deadline;
        return new Result(
            completed, deadlineExpired, cancel.get(),
            producedBox[0], consumedBox[0], elapsed,
            consumedBox[0] / (elapsed / 1e9),
            failure.get());
    }

    public static void main(String[] args) throws InterruptedException {
        long items = 50_000_000L;
        int capacity = 1024;
        String cursorMode = "cached";
        int batch = 1;
        long deadlineSeconds = 120;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--items" -> items = Long.parseLong(args[i + 1]);
                case "--capacity" -> capacity = Integer.parseInt(args[i + 1]);
                case "--cursor-mode" -> cursorMode = args[i + 1];
                case "--batch" -> batch = Integer.parseInt(args[i + 1]);
                case "--deadline-seconds" -> deadlineSeconds = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Result result = runTransfer(new Config(
            items, capacity, cursorMode.equals("cached"), batch,
            TimeUnit.SECONDS.toNanos(deadlineSeconds)));

        System.out.println("{");
        System.out.println("  \"harness\": \"SpscTransferHarness (finite N, deadline, shared cancellation)\",");
        System.out.println("  \"items\": " + items + ", \"capacity\": " + capacity
            + ", \"cursorMode\": \"" + cursorMode + "\", \"batch\": " + batch + ",");
        System.out.println("  \"completed\": " + result.completed() + ",");
        System.out.println("  \"deadlineExpired\": " + result.deadlineExpired() + ",");
        System.out.println("  \"cancelled\": " + result.cancelled() + ",");
        System.out.println("  \"produced\": " + result.produced() + ", \"consumed\": " + result.consumed() + ",");
        System.out.println("  \"elapsedNs\": " + result.elapsedNanos() + ",");
        System.out.println("  \"itemsPerSecond\": " + String.format(java.util.Locale.ROOT, "%.3f", result.itemsPerSecond()) + ",");
        System.out.println("  \"failure\": " + (result.failure() == null ? "null" : "\"" + result.failure().replace("\"", "'") + "\""));
        System.out.println("}");
        if (!result.completed()) {
            System.exit(1);
        }
    }
}
