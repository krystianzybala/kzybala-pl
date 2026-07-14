package pl.kzybala.lab.memoryordering;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Companion code for the "Memory ordering in Java and Rust" Performance Lab
 * (kzybala.pl/lab/memory-ordering/). The fix for {@link PlainPublication}:
 * a release write on the flag flushes everything published before it in
 * program order, and an acquire read that observes it is guaranteed to see
 * that data too. See java.md and the interactive model's "Release/acquire
 * message passing" scenario.
 */
public class ReleaseAcquirePublication {
    private static final VarHandle DATA, FLAG;

    static {
        try {
            var lookup = MethodHandles.lookup();
            DATA = lookup.findVarHandle(ReleaseAcquirePublication.class, "data", int.class);
            FLAG = lookup.findVarHandle(ReleaseAcquirePublication.class, "flag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private int data;
    private int flag;

    /** Publisher thread. */
    public void publish() {
        DATA.set(this, 42); // plain — ordering comes from the release below
        FLAG.setRelease(this, 1);
    }

    /**
     * Observer thread. Once this observes {@code flag == 1} via {@code
     * getAcquire}, the plain read of {@code data} is guaranteed to see the
     * publisher's write — unlike {@link PlainPublication#tryConsume()}.
     */
    public boolean tryConsume() {
        if ((int) FLAG.getAcquire(this) == 1) {
            int seen = (int) DATA.get(this);
            return seen == 42;
        }
        return false;
    }
}
