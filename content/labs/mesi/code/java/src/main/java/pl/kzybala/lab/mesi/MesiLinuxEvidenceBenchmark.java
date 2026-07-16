package pl.kzybala.lab.mesi;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Publication-evidence benchmark for the native-Linux runner: MEASURED
 * coherence behavior, deliberately separate from the lab's educational
 * MESI simulator (the interactive model teaches protocol states; this
 * benchmark measures observable costs — the hardware evidence supports
 * "coherence/ownership-transfer" conclusions, never a claim that a line
 * was in an exact MESI state at an exact moment).
 *
 * <p>Two pinned workers (A→plab.cpuA, B→plab.cpuB) over an
 * {@link AtomicLongArray}: slot {@link #SLOT_X} is the observed line;
 * slot {@link #SLOT_Y} sits ≥ 256 bytes away — a different line under any
 * plausible line size. One operation = one atomic increment (or one
 * consumed read for reader roles); exact-count semantics are the
 * correctness oracle.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>{@code singleWriter} — A writes slot X exclusively; B does purely
 *     thread-local work (no shared memory): the exclusive-ownership write
 *     baseline;</li>
 *   <li>{@code sharedReaders} — both workers only read slot X (written
 *     once in setup): the Shared-state read baseline, no invalidations;</li>
 *   <li>{@code writerInvalidation} — A writes slot X, B reads slot X:
 *     write→read-miss invalidation traffic;</li>
 *   <li>{@code pingPong} — both write slot X: maximal ownership
 *     transfer;</li>
 *   <li>{@code paddedLines} — A writes slot X, B writes slot Y: the same
 *     two-writer work with no line shared — the control that isolates
 *     coherence traffic from the write cost itself.</li>
 * </ul>
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MesiLinuxEvidenceBenchmark {

    static final int SLOT_X = 8;
    static final int SLOT_Y = 40; // 32 slots (256 bytes) away from X

    @Param({"singleWriter", "sharedReaders", "writerInvalidation", "pingPong", "paddedLines"})
    public String scenario;

    private AtomicLongArray slots;

    @State(Scope.Thread)
    public static class PinA {
        WorkerPin pin;
        long localWork;

        @Setup(Level.Trial)
        public void pin() {
            if (WorkerPin.pinningRequested()) pin = WorkerPin.establish("workerA", WorkerPin.CPU_A);
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @State(Scope.Thread)
    public static class PinB {
        WorkerPin pin;
        long localWork;

        @Setup(Level.Trial)
        public void pin() {
            if (WorkerPin.pinningRequested()) pin = WorkerPin.establish("workerB", WorkerPin.CPU_B);
        }

        @TearDown(Level.Trial)
        public void verify() {
            if (pin != null) pin.verifyAndRecord();
        }
    }

    @Setup(Level.Trial)
    public void setUp() {
        slots = new AtomicLongArray(64);
        slots.set(SLOT_X, 1); // published once — what sharedReaders read
    }

    @Benchmark
    @Group("coherence")
    public long roleA(PinA pinned) {
        return switch (scenario) {
            case "singleWriter", "writerInvalidation", "pingPong" -> slots.incrementAndGet(SLOT_X);
            case "sharedReaders" -> slots.get(SLOT_X);
            case "paddedLines" -> slots.incrementAndGet(SLOT_X);
            default -> throw new IllegalStateException("unknown scenario: " + scenario);
        };
    }

    @Benchmark
    @Group("coherence")
    public long roleB(PinB pinned) {
        return switch (scenario) {
            case "singleWriter" -> ++pinned.localWork;      // no shared memory at all
            case "sharedReaders", "writerInvalidation" -> slots.get(SLOT_X);
            case "pingPong" -> slots.incrementAndGet(SLOT_X);
            case "paddedLines" -> slots.incrementAndGet(SLOT_Y);
            default -> throw new IllegalStateException("unknown scenario: " + scenario);
        };
    }

    @TearDown(Level.Trial)
    public void validate() {
        boolean xWritten = !scenario.equals("sharedReaders");
        if (xWritten && slots.get(SLOT_X) <= 1) {
            throw new IllegalStateException("slot X never advanced — a writer was starved; run is invalid");
        }
        if (scenario.equals("paddedLines") && slots.get(SLOT_Y) == 0) {
            throw new IllegalStateException("slot Y never advanced — worker B was starved; run is invalid");
        }
    }
}
