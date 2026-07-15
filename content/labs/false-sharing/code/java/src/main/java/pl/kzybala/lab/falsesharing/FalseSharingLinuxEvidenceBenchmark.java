package pl.kzybala.lab.falsesharing;

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
import org.openjdk.jmh.infra.ThreadParams;

import java.util.concurrent.TimeUnit;

/**
 * Publication-evidence benchmark for the native-Linux evidence runner
 * ({@code scripts/performance-lab/run-linux-evidence.sh}). One benchmark
 * class, one {@code layout} parameter, so a single variant can be selected
 * per JVM invocation with {@code -p layout=shared} or {@code -p
 * layout=padded} — the runner never profiles two variants in the same
 * invocation, keeping warm-up and profiler evidence unambiguously
 * attributed.
 *
 * <p>Contract (enforced here and by {@code CounterCorrectnessTest} /
 * {@code CounterLayoutTest}):
 * <ul>
 *   <li>exactly two benchmark worker threads — one writer per counter;
 *     {@link #setUp(ThreadParams)} aborts the run otherwise;</li>
 *   <li>no allocation, clock read, random-number generation or setup work
 *     in the measured methods — each invocation is a single {@code
 *     volatile} increment of a pre-allocated counter;</li>
 *   <li>no locks and no atomic read-modify-writes that would obscure the
 *     tested effect — plain {@code volatile} writes, identical in both
 *     layouts;</li>
 *   <li>no dead-code elimination — the counters are {@code volatile}
 *     instance fields of a shared state object, so every write is a
 *     required side effect; {@link #validate()} additionally fails the run
 *     if either counter did not advance;</li>
 *   <li>layout is verified, not assumed — {@code CounterLayoutTest} checks
 *     the real field offsets with JOL at test time.</li>
 * </ul>
 *
 * <p>The {@code layout} parameter is constant for the lifetime of a fork,
 * so the branch in the measured methods is perfectly predicted and
 * identical in cost across both variants — it never becomes the thing
 * being measured. Warm-up/measurement defaults below are development-grade;
 * the runner always passes the resolved publication profile explicitly
 * ({@code -f -wi -w -i -r -t}).
 */
@State(Scope.Group)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class FalseSharingLinuxEvidenceBenchmark {

    @Param({"shared", "padded"})
    public String layout;

    private SharedCounters shared;
    private PaddedCounters padded;
    private boolean useShared;

    @Setup(Level.Trial)
    public void setUp(ThreadParams threads) {
        if (threads.getGroupThreadCount() != 2) {
            throw new IllegalStateException(
                "false-sharing evidence requires exactly 2 group threads (one writer per counter), got "
                    + threads.getGroupThreadCount() + " — run with -t 2");
        }
        // Both layouts are always allocated so the two variants' measured
        // code differs only in which line the written field occupies —
        // never in null checks or allocation history.
        shared = new SharedCounters();
        padded = new PaddedCounters();
        useShared = switch (layout) {
            case "shared" -> true;
            case "padded" -> false;
            default -> throw new IllegalStateException("unknown layout: " + layout);
        };
    }

    @Benchmark
    @Group("counters")
    public void writeA() {
        if (useShared) {
            shared.counterA++;
        } else {
            padded.counterA++;
        }
    }

    @Benchmark
    @Group("counters")
    public void writeB() {
        if (useShared) {
            shared.counterB++;
        } else {
            padded.counterB++;
        }
    }

    @TearDown(Level.Trial)
    public void validate() {
        long a = useShared ? shared.counterA : padded.counterA;
        long b = useShared ? shared.counterB : padded.counterB;
        if (a <= 0 || b <= 0) {
            throw new IllegalStateException(
                "counter did not advance (a=" + a + ", b=" + b
                    + ") — a writer thread was starved or the write was eliminated; run is invalid");
        }
    }
}
