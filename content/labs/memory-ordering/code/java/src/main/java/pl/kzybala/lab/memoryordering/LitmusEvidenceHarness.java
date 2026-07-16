package pl.kzybala.lab.memoryordering;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outcome-frequency litmus harness for the native-Linux evidence runner —
 * deliberately NOT a JMH benchmark: rare-outcome occurrence is a count
 * over trials, not a latency, and must never be measured with average-time
 * modes. Two PERSISTENT workers (pinned via {@code -Dplab.cpuA/B} when
 * requested) iterate trials coordinated by a sense-reversing spin barrier;
 * every trial runs both halves on fresh (reset) state and records the
 * observed result tuple.
 *
 * <p>Tests:
 * <ul>
 *   <li>{@code mp} (message passing) — T0: data=1 then flag=1; T1:
 *     r1=flag then r2=data. Forbidden under release/acquire and volatile:
 *     {@code r1==1 && r2==0}. Observable in principle under plain access
 *     (compiler/CPU reordering).</li>
 *   <li>{@code sb} (store buffering) — T0: x=1 then r1=y; T1: y=1 then
 *     r2=x. Forbidden under volatile (SeqCst analogue): {@code r1==0 &&
 *     r2==0}. Observable under opaque/relaxed-like access.</li>
 * </ul>
 *
 * <p>Output: one JSON document with total trials, every outcome count, the
 * forbidden-outcome definition and its count — and the explicit statement
 * that ZERO forbidden observations in a finite run is evidence, not proof
 * (this lab's core epistemological lesson).
 *
 * <p>Usage: {@code java -cp benchmarks.jar ...LitmusEvidenceHarness
 * --test mp --mode plain|acqrel|volatile --trials 2000000}
 * (sb modes: opaque|volatile).
 */
public final class LitmusEvidenceHarness {

    private static final VarHandle DATA;
    private static final VarHandle FLAG;

    static {
        try {
            var lookup = MethodHandles.lookup();
            DATA = lookup.findVarHandle(LitmusEvidenceHarness.class, "data", int.class);
            FLAG = lookup.findVarHandle(LitmusEvidenceHarness.class, "flag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unused")
    private volatile int data; // accessed only through DATA with explicit modes
    @SuppressWarnings("unused")
    private volatile int flag; // accessed only through FLAG with explicit modes

    private final String test;
    private final String mode;
    volatile int r1;
    volatile int r2;

    LitmusEvidenceHarness(String test, String mode) {
        this.test = test;
        this.mode = mode;
    }

    void reset() {
        DATA.setVolatile(this, 0);
        FLAG.setVolatile(this, 0);
        r1 = -1;
        r2 = -1;
    }

    // --- the two halves, mode-dispatched (mode constant per process) --------

    void half0() {
        switch (test) {
            case "mp" -> {
                switch (mode) {
                    case "plain" -> { DATA.set(this, 1); FLAG.set(this, 1); }
                    case "acqrel" -> { DATA.set(this, 1); FLAG.setRelease(this, 1); }
                    case "volatile" -> { DATA.setVolatile(this, 1); FLAG.setVolatile(this, 1); }
                    default -> throw new IllegalArgumentException("mp mode: plain|acqrel|volatile");
                }
            }
            case "sb" -> {
                switch (mode) {
                    case "opaque" -> { DATA.setOpaque(this, 1); r1 = (int) FLAG.getOpaque(this); }
                    case "volatile" -> { DATA.setVolatile(this, 1); r1 = (int) FLAG.getVolatile(this); }
                    default -> throw new IllegalArgumentException("sb mode: opaque|volatile");
                }
            }
            default -> throw new IllegalArgumentException("test: mp|sb");
        }
    }

    void half1() {
        switch (test) {
            case "mp" -> {
                switch (mode) {
                    case "plain" -> { r1 = (int) FLAG.get(this); r2 = (int) DATA.get(this); }
                    case "acqrel" -> { r1 = (int) FLAG.getAcquire(this); r2 = (int) DATA.get(this); }
                    case "volatile" -> { r1 = (int) FLAG.getVolatile(this); r2 = (int) DATA.getVolatile(this); }
                    default -> throw new IllegalArgumentException("mp mode: plain|acqrel|volatile");
                }
            }
            case "sb" -> {
                switch (mode) {
                    case "opaque" -> { FLAG.setOpaque(this, 1); r2 = (int) DATA.getOpaque(this); }
                    case "volatile" -> { FLAG.setVolatile(this, 1); r2 = (int) DATA.getVolatile(this); }
                    default -> throw new IllegalArgumentException("sb mode: opaque|volatile");
                }
            }
            default -> throw new IllegalArgumentException("test: mp|sb");
        }
    }

    boolean isForbidden(int observed1, int observed2) {
        return switch (test) {
            // MP forbidden (for ordered modes): saw the flag but stale data.
            case "mp" -> observed1 == 1 && observed2 == 0;
            // SB forbidden (for volatile): both threads read 0.
            case "sb" -> observed1 == 0 && observed2 == 0;
            default -> throw new IllegalStateException(test);
        };
    }

    String forbiddenDescription() {
        return switch (test) {
            case "mp" -> "r1==1 && r2==0 (flag observed, data stale)";
            case "sb" -> "r1==0 && r2==0 (both loads miss both stores)";
            default -> throw new IllegalStateException(test);
        };
    }

    /** Sense-reversing two-party spin barrier — no parking, no allocation. */
    static final class SpinBarrier {
        private final AtomicInteger arrived = new AtomicInteger();
        private volatile int generation;

        void await() {
            int gen = generation;
            if (arrived.incrementAndGet() == 2) {
                arrived.set(0);
                generation = gen + 1;
            } else {
                while (generation == gen) {
                    Thread.onSpinWait();
                }
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        String test = "mp";
        String mode = "plain";
        long trials = 1_000_000;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--test" -> test = args[i + 1];
                case "--mode" -> mode = args[i + 1];
                case "--trials" -> trials = Long.parseLong(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        final LitmusEvidenceHarness harness = new LitmusEvidenceHarness(test, mode);
        final SpinBarrier start = new SpinBarrier();
        final SpinBarrier end = new SpinBarrier();
        final long totalTrials = trials;
        final long[][] outcomes = new long[2][2]; // [r1][r2]
        long forbidden = 0;

        Integer cpu0 = WorkerPin.CPU_A;
        Integer cpu1 = WorkerPin.CPU_B;
        final boolean pin = WorkerPin.pinningRequested();

        Thread worker1 = new Thread(() -> {
            WorkerPin p = pin ? WorkerPin.establish("litmus1", cpu1) : null;
            for (long t = 0; t < totalTrials; t++) {
                start.await();
                harness.half1();
                end.await();
            }
            if (p != null) p.verifyAndRecord();
        });
        worker1.start();

        WorkerPin p0 = pin ? WorkerPin.establish("litmus0", cpu0) : null;
        for (long t = 0; t < totalTrials; t++) {
            harness.reset();
            start.await();
            harness.half0();
            end.await();
            int o1 = harness.r1;
            int o2 = harness.r2;
            // clamp unset (-1) defensively — must never happen after the barrier
            if (o1 < 0 || o1 > 1 || o2 < 0 || o2 > 1) {
                throw new IllegalStateException("observer registers unset after trial " + t + " (r1=" + o1 + ", r2=" + o2 + ")");
            }
            outcomes[o1][o2]++;
            if (harness.isForbidden(o1, o2)) forbidden++;
        }
        worker1.join();
        if (p0 != null) p0.verifyAndRecord();

        long sum = outcomes[0][0] + outcomes[0][1] + outcomes[1][0] + outcomes[1][1];
        if (sum != totalTrials) {
            throw new IllegalStateException("outcome counts (" + sum + ") do not sum to trials (" + totalTrials + ")");
        }
        System.out.println("{");
        System.out.println("  \"harness\": \"LitmusEvidenceHarness (persistent workers, outcome counts — not latency)\",");
        System.out.println("  \"test\": \"" + test + "\", \"mode\": \"" + mode + "\",");
        System.out.println("  \"trials\": " + totalTrials + ",");
        System.out.println("  \"outcomes\": { \"r1=0,r2=0\": " + outcomes[0][0] + ", \"r1=0,r2=1\": " + outcomes[0][1]
            + ", \"r1=1,r2=0\": " + outcomes[1][0] + ", \"r1=1,r2=1\": " + outcomes[1][1] + " },");
        System.out.println("  \"forbiddenOutcome\": \"" + harness.forbiddenDescription() + "\",");
        System.out.println("  \"forbiddenCount\": " + forbidden + ",");
        System.out.println("  \"pinned\": " + pin + ",");
        System.out.println("  \"note\": \"zero forbidden observations in a finite run is evidence consistent with the ordering claim, never a proof of it; one forbidden observation under an ordered mode falsifies the implementation or the model\"");
        System.out.println("}");
    }
}
