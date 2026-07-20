package pl.kzybala.lab.clockslatency;

import org.HdrHistogram.Histogram;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

/**
 * What does OBSERVING latency cost the hot path? One fixed-cost operation
 * (a single xorshift64 step over benchmark state), measured bare and under
 * three instrumentation strategies — the instrument's price list:
 *
 * - {@link #baselineOp()} — the operation alone (the control);
 * - {@link #timestampEveryOp()} — two {@code System.nanoTime()} calls and
 *   an allocation-free histogram record per operation;
 * - {@link #sampledTimestamp()} — full instrumentation on every 64th
 *   operation only (the sampling trade-off: cheaper, but the tail fixture
 *   shows what sampling does to p99/p999 confidence);
 * - {@link #recordOnly()} — the histogram record alone, timestamps
 *   excluded (separates clock cost from recording cost).
 *
 * Timer overhead is derived, never guessed:
 * {@code timestampEveryOp − baselineOp} ≈ 2×nanoTime + record;
 * {@code recordOnly − baselineOp} ≈ record alone. The histogram is
 * preallocated in setup (fixed range, no auto-resize) so the measured
 * path allocates nothing — verified by {@code -prof gc}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class RecordingCostBenchmark {

    long state;
    Histogram histogram;
    int tick;
    WorkerPin pin;

    @Setup(Level.Trial)
    public void setUp() {
        state = 42;
        histogram = LatencyHistograms.newHistogram();
        tick = 0;
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    long op() {
        state = SyntheticLatency.xorshift64(state);
        return state;
    }

    @Benchmark
    public long baselineOp() {
        return op();
    }

    @Benchmark
    public long timestampEveryOp() {
        long t0 = System.nanoTime();
        long r = op();
        long t1 = System.nanoTime();
        histogram.recordValue(Math.max(1, t1 - t0));
        return r;
    }

    @Benchmark
    public long sampledTimestamp() {
        if ((++tick & 63) == 0) {
            long t0 = System.nanoTime();
            long r = op();
            long t1 = System.nanoTime();
            histogram.recordValue(Math.max(1, t1 - t0));
            return r;
        }
        return op();
    }

    @Benchmark
    public long recordOnly() {
        long r = op();
        // fixed plausible value: isolates pure recording cost from clock cost
        histogram.recordValue(1000);
        return r;
    }
}
