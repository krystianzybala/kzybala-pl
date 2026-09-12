package pl.kzybala.lab.memord;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Sequence flag plus payload: a classic seqlock. A single writer thread
 * performs MemOrdFixtures.SEQFLAG_UPDATE_COUNT sequential updates, each
 * bracketed by an ODD seq (update i, "in progress") then an EVEN seq
 * (2*i, "published i"); one reader thread reads seq, then payload, then
 * seq again, retrying whenever the two seq reads disagree or land on an
 * odd (in-progress) value — the standard technique for getting an
 * atomic-enough snapshot of a plain payload guarded by a cheap flag,
 * and the reason this dataset is named "sequence flag plus payload"
 * rather than "single flag plus payload" (see MailboxKernel for that
 * simpler, one-shot case).
 */
public final class SequenceFlagKernel {
    private SequenceFlagKernel() {}

    public enum Outcome { CORRECT, FORBIDDEN_STALE_PAYLOAD, TIMED_OUT }

    static final class Cell {
        int payload;
        int seq; // 0 = not started; odd = update in progress; even = published (seq/2 = update index)
    }

    private static final VarHandle SEQ;
    static {
        try {
            SEQ = MethodHandles.lookup().findVarHandle(Cell.class, "seq", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private interface SeqStore {
        void store(Cell c, int seq);
    }

    private interface SeqLoad {
        int load(Cell c);
    }

    /**
     * A real bug found while building this lab: reading {@code payload}
     * (plain) between two seq reads is NOT safely bracketed by ordering
     * alone, even at full volatile/seq-cst strength. Per the JSR-133
     * reordering table, "NormalLoad followed by VolatileLoad" is a
     * permitted reordering — nothing stops the second seq load from
     * being executed before the payload load actually completes, which
     * lets a same-value (s1 == s2) illusion coexist with a payload that
     * was already overwritten by a later writer update. A first version
     * of this kernel without {@code readBarrier} failed intermittently
     * for EVERY variant, including volatileSeqCstPublication — proof
     * this was a real gap, not a hardware-reordering artifact of a
     * specific variant. The fix: an explicit {@code loadLoadFence()}
     * between the payload read and the second seq read, independent of
     * whichever ordering the seq accesses themselves use — the seqlock
     * double-check pattern needs this fence regardless of publication
     * mechanism. The plain variant deliberately omits it, preserving it
     * as the demonstration baseline.
     */
    private static Outcome run(SeqStore store, SeqLoad load, Runnable readBarrier) throws InterruptedException {
        Cell c = new Cell();
        Thread writerThread = new Thread(() -> {
            for (int i = 1; i <= MemOrdFixtures.SEQFLAG_UPDATE_COUNT; i++) {
                store.store(c, 2 * i - 1); // odd: update in progress
                c.payload = MemOrdFixtures.seqflagPayload(i);
                store.store(c, 2 * i);     // even: update i published
            }
        });
        final Outcome[] result = {Outcome.TIMED_OUT};
        Thread readerThread = new Thread(() -> {
            long spins = 0;
            while (true) {
                int s1 = load.load(c);
                if (s1 > 0 && (s1 & 1) == 0) { // even: not mid-update
                    int payload = c.payload; // plain read, bracketed by seq reads
                    readBarrier.run();
                    int s2 = load.load(c);
                    if (s1 == s2) { // consistent snapshot
                        int i = s1 / 2;
                        if (payload != MemOrdFixtures.seqflagPayload(i)) {
                            result[0] = Outcome.FORBIDDEN_STALE_PAYLOAD;
                            return;
                        }
                        if (i >= MemOrdFixtures.SEQFLAG_UPDATE_COUNT) {
                            result[0] = Outcome.CORRECT;
                            return;
                        }
                    }
                }
                if (++spins >= MemOrdFixtures.MAX_SPIN_ITERATIONS) {
                    result[0] = Outcome.TIMED_OUT;
                    return;
                }
                if ((spins & 0xFFFF) == 0) {
                    Thread.onSpinWait();
                }
            }
        });
        readerThread.start();
        writerThread.start();
        writerThread.join();
        readerThread.join();
        return result[0];
    }

    public static Outcome plainBrokenPublication() throws InterruptedException {
        return run(
                (c, seq) -> c.seq = seq, // plain store
                c -> c.seq,              // plain load
                () -> {}                 // no read barrier — the broken baseline
        );
    }

    public static Outcome acquireReleasePublication() throws InterruptedException {
        return run(
                (c, seq) -> SEQ.setRelease(c, seq),
                c -> (int) SEQ.getAcquire(c),
                VarHandle::loadLoadFence
        );
    }

    public static Outcome volatileSeqCstPublication() throws InterruptedException {
        return run(
                (c, seq) -> SEQ.setVolatile(c, seq),
                c -> (int) SEQ.getVolatile(c),
                VarHandle::loadLoadFence
        );
    }

    /** Single writer, so each CAS succeeds on its first attempt — the
     * retry loop is real wiring, not a no-op stand-in, mirroring the
     * multi-writer counter-update dataset's CAS variant. */
    public static Outcome casLoop() throws InterruptedException {
        return run(
                (c, seq) -> {
                    int prev = seq - 1;
                    while (!SEQ.compareAndSet(c, prev, seq)) {
                        Thread.onSpinWait();
                    }
                },
                c -> (int) SEQ.getAcquire(c),
                VarHandle::loadLoadFence
        );
    }

    /**
     * A second real bug found while building this lab, reproduced
     * repeatedly on real hardware (Apple M1 Max/ARM), not merely
     * theorised: two earlier attempts at a genuinely "explicit standalone
     * fences instead of ordered accessors" implementation of this
     * variant — first with plain {@code c.seq} access, then with
     * {@code Opaque} {@code c.seq} access, both bracketed only by
     * {@code releaseFence()}/{@code acquireFence()}/{@code
     * loadLoadFence()} — failed intermittently across repeated full-suite
     * runs (roughly 1 run in 3–5), even though each individually looked
     * correct against the JSR-133/JEP-193 fence-composition rules
     * (release = StoreStore+LoadStore, acquire = LoadLoad+LoadStore,
     * neither alone supplies a StoreLoad edge). Escalating the read-side
     * barrier to a full {@code fullFence()} reduced but did not eliminate
     * the failure; only reverting the SEQ field itself to genuine
     * {@code setRelease}/{@code getAcquire} access modes (matching
     * {@link #acquireReleasePublication()}) made the failure disappear
     * across repeated runs. The honest conclusion documented here: on
     * this lab's toolchain, a standalone {@code releaseFence()} paired
     * with a separate {@code Opaque} store is NOT a reliably-observed
     * substitute for {@code setRelease} for this rapid, 2000-iteration
     * publish loop — whether that gap is a genuine hardware/JIT interplay
     * or an implementation subtlety this lab did not fully isolate is
     * itself part of the lesson: "should be equivalent per the spec" and
     * "empirically holds under this JVM/CPU" are different claims, and a
     * teaching lab must report the one it actually verified. This variant
     * therefore keeps the proven {@code setRelease}/{@code getAcquire}
     * backbone and layers one additional explicit {@code fullFence()} on
     * publish, to still demonstrate standalone fences as a real technique
     * (the belt-and-suspenders pattern some seqlock/RCU implementations
     * use) without shipping a variant this lab could not itself verify as
     * reliable. See java.md for the reproduction summary.
     */
    public static Outcome fenceBased() throws InterruptedException {
        return run(
                (c, seq) -> {
                    SEQ.setRelease(c, seq);
                    VarHandle.fullFence(); // extra explicit StoreLoad edge, see class javadoc below
                },
                c -> (int) SEQ.getAcquire(c),
                VarHandle::loadLoadFence
        );
    }
}
