package pl.kzybala.lab.harnesstraps;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * One benchmark class, six measurement shapes of the SAME work — the trap
 * variants and the corrected variants call the identical {@link TrapKernels}
 * code, so any difference in the reported ns/op is a property of the
 * harness, not of the kernel:
 *
 * <ul>
 *   <li>{@link #foldedInput()} vs {@link #runtimeInput()} — the input is a
 *       compile-time constant the JIT may fold vs a runtime value from
 *       benchmark state;</li>
 *   <li>{@link #returnedResult()} vs {@link #consumedResult(Blackhole)} —
 *       two valid anti-dead-code sinks (JMH consumes the return value
 *       implicitly; the Blackhole is explicit) shown side by side; the
 *       actual trap — computing a value nobody observes — is deliberately
 *       NOT included as a benchmark because it measures nothing;</li>
 *   <li>{@link #setupInsideTimed()} vs {@link #setupOutside()} — dataset
 *       construction inside the measured operation vs in {@code @Setup};</li>
 *   <li>single-fork vs isolated-fork runs are a harness CONFIGURATION
 *       contrast: same methods, {@code -f 1} vs the profile's fork count
 *       (see the lab's runner configuration and benchmark.md).</li>
 * </ul>
 *
 * One benchmark operation = one full kernel pass over the selected dataset
 * (scalar: {@code rounds} mixes; reduction: one sum over the array; parser:
 * one parse of the prebuilt input; counter: {@code steps} advances from the
 * fixture seed — reset per invocation so no state leaks between
 * invocations). Setup, dataset generation and validation live outside the
 * timed region except in {@code setupInsideTimed}, where the placement IS
 * the experiment.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class HarnessTrapsBenchmark {

    // Fixture constants (code/fixtures/benchmark-harness-traps-fixtures.json):
    // compile-time constants by design — foldedInput() exists to show what
    // the JIT does when the whole computation is visible at compile time.
    static final long SCALAR_SEED = 42L;
    static final int SCALAR_ROUNDS = 1000;
    static final long REDUCTION_SEED = 42L;
    static final int REDUCTION_LENGTH = 4096;
    static final long PARSER_SEED = 7L;
    static final int PARSER_COUNT = 256;
    static final long COUNTER_SEED = 0L;
    static final int COUNTER_STEPS = 10000;

    @Param({"scalar", "reduction", "parser", "counter"})
    public String dataset;

    // Runtime state: identical VALUES to the constants above, but loaded
    // from fields the JIT must treat as changeable inputs.
    long runtimeSeed;
    int runtimeRounds;
    long[] reductionData;
    String parserInput;
    TrapKernels.StatefulCounter counter;

    WorkerPin pin;

    @Setup(Level.Trial)
    public void setUp() {
        runtimeSeed = SCALAR_SEED;
        runtimeRounds = SCALAR_ROUNDS;
        reductionData = TrapKernels.fillArray(REDUCTION_SEED, REDUCTION_LENGTH);
        parserInput = TrapKernels.buildParserInput(PARSER_SEED, PARSER_COUNT);
        counter = new TrapKernels.StatefulCounter(COUNTER_SEED);
        // Native-Linux evidence runs pin the single worker (-Dplab.cpuA);
        // development runs without the property are unpinned and never
        // publication evidence.
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("worker", WorkerPin.CPU_A);
        }
    }

    // --- constant-foldable input vs runtime input ---------------------------

    /**
     * TRAP: every input is a compile-time constant. The JIT is allowed to
     * evaluate as much of this as it can prove — a result that looks
     * impossibly fast here is the compiler doing the benchmark's work at
     * compile time, not the CPU doing it at run time.
     */
    @Benchmark
    public long foldedInput() {
        return dispatch(SCALAR_SEED, SCALAR_ROUNDS);
    }

    /** CORRECTED: the same values, loaded from benchmark state at run time. */
    @Benchmark
    public long runtimeInput() {
        return dispatch(runtimeSeed, runtimeRounds);
    }

    // --- returned result vs consumed result ---------------------------------

    /** Anti-DCE sink #1: JMH consumes the returned value. */
    @Benchmark
    public long returnedResult() {
        return dispatch(runtimeSeed, runtimeRounds);
    }

    /** Anti-DCE sink #2: the result is fed to the Blackhole explicitly. */
    @Benchmark
    public void consumedResult(Blackhole bh) {
        bh.consume(dispatch(runtimeSeed, runtimeRounds));
    }

    // --- setup inside the timed region vs outside ---------------------------

    /**
     * TRAP: the dataset is rebuilt inside the measured operation, so the
     * reported cost is dominated by setup the experiment never meant to
     * measure.
     */
    @Benchmark
    public long setupInsideTimed() {
        return dispatchWithSetupInside();
    }

    /** CORRECTED: the dataset comes from {@code @Setup}; only the kernel is timed. */
    @Benchmark
    public long setupOutside() {
        return dispatch(runtimeSeed, runtimeRounds);
    }

    // --- shared dispatch -----------------------------------------------------

    /**
     * Kept out of line so every variant measures the same call shape;
     * inlining differences between variants would be a confound, not a
     * finding.
     */
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    long dispatch(long seed, int rounds) {
        switch (dataset) {
            case "scalar":
                return TrapKernels.mixScalar(seed, rounds);
            case "reduction":
                return TrapKernels.reduce(reductionData);
            case "parser":
                return TrapKernels.parseChecksum(parserInput, PARSER_COUNT);
            case "counter":
                // reset per invocation: the corrected form of the stateful
                // dataset — state never leaks between invocations
                counter.reset(COUNTER_SEED);
                long last = COUNTER_SEED;
                for (int i = 0; i < COUNTER_STEPS; i++) {
                    last = counter.advance();
                }
                return last;
            default:
                throw new IllegalStateException("unknown dataset " + dataset);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    long dispatchWithSetupInside() {
        // The trap: dataset construction INSIDE the timed region.
        switch (dataset) {
            case "scalar":
                // the scalar dataset has no setup to move inside — the
                // setup-placement contrast is null here by design (the
                // runner measures this pair on the parser dataset)
                return TrapKernels.mixScalar(runtimeSeed, runtimeRounds);
            case "reduction": {
                long[] data = TrapKernels.fillArray(REDUCTION_SEED, REDUCTION_LENGTH);
                return TrapKernels.reduce(data);
            }
            case "parser": {
                String input = TrapKernels.buildParserInput(PARSER_SEED, PARSER_COUNT);
                return TrapKernels.parseChecksum(input, PARSER_COUNT);
            }
            case "counter": {
                TrapKernels.StatefulCounter fresh = new TrapKernels.StatefulCounter(COUNTER_SEED);
                long last = COUNTER_SEED;
                for (int i = 0; i < COUNTER_STEPS; i++) {
                    last = fresh.advance();
                }
                return last;
            }
            default:
                throw new IllegalStateException("unknown dataset " + dataset);
        }
    }
}
