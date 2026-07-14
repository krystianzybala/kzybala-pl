package pl.kzybala.lab.memoryordering;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Companion code for the "Memory ordering in Java and Rust" Performance Lab
 * (kzybala.pl/lab/memory-ordering/). {@link #incrementAtomically()} is a
 * genuine atomic read-modify-write (unlike {@link OpaqueCounter#increment()}):
 * always correct regardless of interleaving. That is the atomicity
 * guarantee alone — it says nothing about the ordering of any other,
 * unrelated field. See java.md and the interactive model's "Relaxed
 * counter" scenario.
 */
public class VolatileCounter {
    private static final VarHandle COUNTER;

    static {
        try {
            COUNTER = MethodHandles.lookup().findVarHandle(VolatileCounter.class, "counter", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile int counter;

    public void incrementAtomically() {
        COUNTER.getAndAdd(this, 1);
    }

    public int get() {
        return counter;
    }
}
