package pl.kzybala.lab.memoryordering;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

/**
 * OPERATION-COST benchmark — the deliberately separate second half of this
 * lab's measured story (outcome frequencies live in
 * {@link LitmusEvidenceHarness}; this class never counts rare outcomes and
 * the harness never measures latency). One pinned worker; one operation =
 * one publication (plain payload write + flag store at the selected access
 * mode). What varies is exclusively the ordering strength of the flag
 * store — the per-operation price of `plain` vs `opaque` vs `release` vs
 * `volatile` on this hardware.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MemoryOrderingCostBenchmark {

    private static final VarHandle FLAG;

    static {
        try {
            FLAG = MethodHandles.lookup().findVarHandle(MemoryOrderingCostBenchmark.class, "flag", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Param({"plain", "opaque", "release", "volatile"})
    public String accessMode;

    private long payload;
    @SuppressWarnings("unused")
    private volatile long flag; // accessed only through FLAG with explicit modes
    private long sequence;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setUp() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("publisher", WorkerPin.CPU_A);
        }
        sequence = 0;
    }

    /** One operation = one publish: payload plain write, flag store at the mode. */
    @Benchmark
    public long publish() {
        long value = ++sequence;
        payload = value;
        switch (accessMode) {
            case "plain" -> FLAG.set(this, value);
            case "opaque" -> FLAG.setOpaque(this, value);
            case "release" -> FLAG.setRelease(this, value);
            case "volatile" -> FLAG.setVolatile(this, value);
            default -> throw new IllegalStateException("unknown accessMode: " + accessMode);
        }
        return value; // consumed — no dead-code elimination
    }

    @TearDown(Level.Trial)
    public void validate() {
        long observed = (long) FLAG.getVolatile(this);
        if (observed != sequence || payload != sequence || sequence == 0) {
            throw new IllegalStateException("publication accounting broken (flag=" + observed
                + ", payload=" + payload + ", sequence=" + sequence + ") — run is invalid");
        }
        if (pin != null) pin.verifyAndRecord();
    }
}
