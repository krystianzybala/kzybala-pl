package pl.kzybala.lab.jitpipeline;

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

import java.util.concurrent.TimeUnit;

/**
 * STEADY-STATE cost benchmark (JMH — warm-up discarded by design; the
 * trajectory itself lives in {@link WarmupTrajectoryHarness}, and the two
 * are never merged): the per-pass cost of the shared pricing kernel under
 * different call-site shapes (inlining axis) and with/without the
 * escape-analysis allocation kernel (the EA on/off pair is selected by
 * the runner via per-variant JVM flags — exact flags recorded in the run's
 * toolchain/profile evidence).
 *
 * <ul>
 *   <li>{@code mono} — one concrete receiver: fully inlinable;</li>
 *   <li>{@code bi} — two receivers alternating: bimorphic dispatch;</li>
 *   <li>{@code mega} — three receivers rotating: megamorphic, typically a
 *     virtual call the optimizer cannot devirtualize;</li>
 *   <li>{@code escape} — the allocating holder kernel: compare runs with
 *     default flags against {@code -XX:-DoEscapeAnalysis} (the ea-on /
 *     ea-off variants in the runner config).</li>
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class JitSteadyStateBenchmark {

    @Param({"mono", "bi", "mega", "escape"})
    public String callShape;

    private long[] amounts;
    private PricingKernel.Pricer mono;
    private PricingKernel.Pricer[] bi;
    private PricingKernel.Pricer[] mega;
    private long expectedPerPass;
    private long observed;
    private WorkerPin pin;

    @Setup(Level.Trial)
    public void setUp() {
        if (WorkerPin.pinningRequested()) {
            pin = WorkerPin.establish("jit", WorkerPin.CPU_A);
        }
        amounts = PricingKernel.amounts();
        mono = new PricingKernel.BasicPricer();
        bi = new PricingKernel.Pricer[] {new PricingKernel.BasicPricer(), new PricingKernel.DiscountPricer()};
        mega = new PricingKernel.Pricer[] {
            new PricingKernel.BasicPricer(), new PricingKernel.DiscountPricer(), new PricingKernel.SurgePricer(),
        };
        expectedPerPass = switch (callShape) {
            case "mono", "escape" -> 50215100L;
            case "bi" -> 47716500L; // basic/discount alternating — pinned by the correctness suite
            case "mega" -> 56392272L;
            default -> throw new IllegalStateException("unknown callShape: " + callShape);
        };
        observed = 0;
    }

    /** One operation = one full pass of the kernel over the 1024 inputs. */
    @Benchmark
    public long pass() {
        long total = switch (callShape) {
            case "mono" -> PricingKernel.total(mono, amounts);
            case "bi" -> PricingKernel.mixedTotal(bi, amounts);
            case "mega" -> PricingKernel.mixedTotal(mega, amounts);
            case "escape" -> PricingKernel.totalWithHolder(mono, amounts);
            default -> throw new IllegalStateException(callShape);
        };
        observed = total;
        return total; // consumed — no dead-code elimination
    }

    @TearDown(Level.Trial)
    public void validate() {
        if (observed != expectedPerPass) {
            throw new IllegalStateException("kernel total " + observed + " != expected " + expectedPerPass + " — run is invalid");
        }
        if (pin != null) pin.verifyAndRecord();
    }
}
