package pl.kzybala.lab.memord;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Single-slot mailbox: one writer publishes a two-field payload exactly
 * once; one fresh reader thread claims and reads it. Five variants of
 * the same protocol, differing only in how the "ready" flag is
 * accessed. See MemOrdFixtures for the shared payload constants.
 */
public final class MailboxKernel {
    private MailboxKernel() {}

    public enum Outcome { CORRECT, FORBIDDEN_STALE_READ, TIMED_OUT }

    /** Payload fields are deliberately PLAIN — the ready flag's access
     * mode is what each variant varies; JMM visibility for a/b flows
     * from the happens-before edge the flag establishes (or fails to
     * establish, in the broken variant). */
    static final class Slot {
        int a;
        int b;
        int ready; // 0 = unpublished, 1 = published, 2 = claimed (CAS variant only)
    }

    private static final VarHandle READY;
    static {
        try {
            READY = MethodHandles.lookup().findVarHandle(Slot.class, "ready", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean spinUntil(java.util.function.BooleanSupplier condition) {
        long i = 0;
        while (!condition.getAsBoolean()) {
            if (++i >= MemOrdFixtures.MAX_SPIN_ITERATIONS) {
                return false;
            }
            if ((i & 0xFFFF) == 0) {
                Thread.onSpinWait();
            }
        }
        return true;
    }

    private static Outcome checkPayload(Slot s) {
        return (s.a == MemOrdFixtures.MAILBOX_PAYLOAD_A && s.b == MemOrdFixtures.MAILBOX_PAYLOAD_B)
                ? Outcome.CORRECT
                : Outcome.FORBIDDEN_STALE_READ;
    }

    /** Demonstration variant: plain field write publishes the flag, no
     * ordering guarantee ties it to the payload writes. This is the
     * "what goes wrong" baseline — its trials are NEVER asserted to
     * fail (that would be a flaky test); the observed forbidden-read
     * rate is reported honestly, whatever it is on a given run. */
    public static Outcome plainBrokenPublication() throws InterruptedException {
        Slot s = new Slot();
        Thread writer = new Thread(() -> {
            s.a = MemOrdFixtures.MAILBOX_PAYLOAD_A;
            s.b = MemOrdFixtures.MAILBOX_PAYLOAD_B;
            s.ready = 1; // plain store — no release semantics
        });
        final Outcome[] result = new Outcome[1];
        Thread reader = new Thread(() -> {
            boolean got = spinUntil(() -> s.ready != 0); // plain load
            result[0] = got ? checkPayload(s) : Outcome.TIMED_OUT;
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();
        return result[0];
    }

    public static Outcome acquireReleasePublication() throws InterruptedException {
        Slot s = new Slot();
        Thread writer = new Thread(() -> {
            s.a = MemOrdFixtures.MAILBOX_PAYLOAD_A;
            s.b = MemOrdFixtures.MAILBOX_PAYLOAD_B;
            READY.setRelease(s, 1);
        });
        final Outcome[] result = new Outcome[1];
        Thread reader = new Thread(() -> {
            boolean got = spinUntil(() -> (int) READY.getAcquire(s) != 0);
            result[0] = got ? checkPayload(s) : Outcome.TIMED_OUT;
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();
        return result[0];
    }

    public static Outcome volatileSeqCstPublication() throws InterruptedException {
        Slot s = new Slot();
        Thread writer = new Thread(() -> {
            s.a = MemOrdFixtures.MAILBOX_PAYLOAD_A;
            s.b = MemOrdFixtures.MAILBOX_PAYLOAD_B;
            READY.setVolatile(s, 1);
        });
        final Outcome[] result = new Outcome[1];
        Thread reader = new Thread(() -> {
            boolean got = spinUntil(() -> (int) READY.getVolatile(s) != 0);
            result[0] = got ? checkPayload(s) : Outcome.TIMED_OUT;
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();
        return result[0];
    }

    /** CAS both publishes (0->1) and claims (1->2) — an exactly-once
     * hand-off, not just a one-way flag. Full-fence semantics either
     * way, at real CAS-retry cost for the reader's claim attempt. */
    public static Outcome casLoop() throws InterruptedException {
        Slot s = new Slot();
        Thread writer = new Thread(() -> {
            s.a = MemOrdFixtures.MAILBOX_PAYLOAD_A;
            s.b = MemOrdFixtures.MAILBOX_PAYLOAD_B;
            if (!READY.compareAndSet(s, 0, 1)) {
                throw new IllegalStateException("unexpected concurrent publisher");
            }
        });
        final Outcome[] result = new Outcome[1];
        Thread reader = new Thread(() -> {
            boolean claimed = spinUntil(() -> READY.compareAndSet(s, 1, 2));
            result[0] = claimed ? checkPayload(s) : Outcome.TIMED_OUT;
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();
        return result[0];
    }

    /** Explicit standalone fences around the flag access, instead of
     * ordered accessors ({@code setRelease}/{@code getAcquire}) on the
     * flag variable itself. The flag access is {@code Opaque}, not plain
     * — a standalone fence only orders memory operations relative to
     * each other within the executing thread; it does not by itself make
     * a PLAIN field's write visible to another thread (see
     * SequenceFlagKernel's fenceBased for the intermittent-failure
     * writeup of this exact trap). Opaque supplies the atomicity/
     * visibility a plain access cannot; the fences supply the ordering
     * that Opaque alone does not. */
    public static Outcome fenceBased() throws InterruptedException {
        Slot s = new Slot();
        Thread writer = new Thread(() -> {
            s.a = MemOrdFixtures.MAILBOX_PAYLOAD_A;
            s.b = MemOrdFixtures.MAILBOX_PAYLOAD_B;
            VarHandle.releaseFence();
            READY.setOpaque(s, 1); // opaque store, ordered by the preceding release fence
        });
        final Outcome[] result = new Outcome[1];
        Thread reader = new Thread(() -> {
            boolean got = spinUntil(() -> (int) READY.getOpaque(s) != 0); // opaque load
            if (got) {
                VarHandle.acquireFence();
            }
            result[0] = got ? checkPayload(s) : Outcome.TIMED_OUT;
        });
        reader.start();
        writer.start();
        writer.join();
        reader.join();
        return result[0];
    }
}
