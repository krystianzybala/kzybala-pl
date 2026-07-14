package pl.kzybala.lab.memoryordering;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Companion code for the "Memory ordering in Java and Rust" Performance Lab
 * (kzybala.pl/lab/memory-ordering/): the classic store-buffering litmus
 * test. Thread 0 writes {@code x} then reads {@code y}; thread 1 writes
 * {@code y} then reads {@code x} — no dependency between the variables at
 * all. Under opaque ("relaxed-like") access, both threads can legally
 * observe 0 for the other's write; under volatile access (closest JVM
 * analogue to SeqCst), that specific outcome should not occur.
 *
 * <p>This is a genuine hardware-timing-dependent phenomenon — see
 * theory.md "Methodology and limitations." {@link #runOnce} is exposed so
 * callers can run it many times and observe the distribution of outcomes,
 * rather than asserting a single racy result is guaranteed either way.
 */
public class StoreBufferingLitmusTest {
    private static final VarHandle X, Y;

    static {
        try {
            var lookup = MethodHandles.lookup();
            X = lookup.findVarHandle(StoreBufferingLitmusTest.class, "x", int.class);
            Y = lookup.findVarHandle(StoreBufferingLitmusTest.class, "y", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public enum Mode { OPAQUE, VOLATILE }

    private int x;
    private int y;

    private void write(Mode mode, VarHandle handle, int value) {
        if (mode == Mode.OPAQUE) handle.setOpaque(this, value);
        else handle.setVolatile(this, value);
    }

    private int read(Mode mode, VarHandle handle) {
        return mode == Mode.OPAQUE ? (int) handle.getOpaque(this) : (int) handle.getVolatile(this);
    }

    /**
     * Runs one instance of the litmus test on fresh state and returns
     * {@code [seenY, seenX]} — what thread 0 observed for {@code y} and
     * what thread 1 observed for {@code x}.
     */
    public static int[] runOnce(Mode mode) throws InterruptedException {
        StoreBufferingLitmusTest test = new StoreBufferingLitmusTest();
        CountDownLatch start = new CountDownLatch(2);
        AtomicIntegerArray result = new AtomicIntegerArray(2);

        Thread t0 = new Thread(() -> {
            start.countDown();
            awaitAll(start);
            test.write(mode, X, 1);
            result.set(0, test.read(mode, Y));
        });
        Thread t1 = new Thread(() -> {
            start.countDown();
            awaitAll(start);
            test.write(mode, Y, 1);
            result.set(1, test.read(mode, X));
        });

        t0.start();
        t1.start();
        t0.join();
        t1.join();
        return new int[] { result.get(0), result.get(1) };
    }

    private static void awaitAll(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
