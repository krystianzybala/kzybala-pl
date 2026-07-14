package pl.kzybala.lab.memoryordering;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Companion code for the "Memory ordering in Java and Rust" Performance Lab
 * (kzybala.pl/lab/memory-ordering/). Plain access gives no ordering
 * guarantee at all: {@link #tryConsume()} may legally observe {@code flag
 * == 1} while still observing {@code data == 0}. See java.md and the
 * interactive model's "Broken publication" scenario.
 */
public class PlainPublication {
    private static final VarHandle DATA, FLAG;

    static {
        try {
            var lookup = MethodHandles.lookup();
            DATA = lookup.findVarHandle(PlainPublication.class, "data", int.class);
            FLAG = lookup.findVarHandle(PlainPublication.class, "flag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private int data;
    private int flag;

    /** Publisher thread. */
    public void publish() {
        DATA.set(this, 42);
        FLAG.set(this, 1);
    }

    /**
     * Observer thread. Returns {@code true} only if it saw the published
     * value; {@code false} either because the flag wasn't set yet, or —
     * the bug this class exists to demonstrate — because the flag was
     * visible but the data wasn't.
     */
    public boolean tryConsume() {
        if ((int) FLAG.get(this) == 1) {
            int seen = (int) DATA.get(this);
            return seen == 42;
        }
        return false;
    }
}
