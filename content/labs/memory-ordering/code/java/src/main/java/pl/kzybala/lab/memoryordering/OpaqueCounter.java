package pl.kzybala.lab.memoryordering;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Companion code for the "Memory ordering in Java and Rust" Performance Lab
 * (kzybala.pl/lab/memory-ordering/). Demonstrates the myth this lab
 * rejects: "atomics automatically make a compound algorithm correct."
 * {@code getOpaque}/{@code setOpaque} each individually give per-location
 * atomicity — but {@link #increment()} is a read <em>then</em> a separate
 * write, so two concurrent callers can both read the same value and both
 * write back the same incremented result, losing an update. See java.md.
 */
public class OpaqueCounter {
    private static final VarHandle COUNTER;

    static {
        try {
            COUNTER = MethodHandles.lookup().findVarHandle(OpaqueCounter.class, "counter", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private int counter;

    /** NOT atomic as a whole — see the class-level warning above. */
    public void increment() {
        int current = (int) COUNTER.getOpaque(this);
        COUNTER.setOpaque(this, current + 1);
    }

    public int get() {
        return (int) COUNTER.getOpaque(this);
    }
}
