package pl.kzybala.lab.jitpipeline;

import org.junit.jupiter.api.Test;
import org.openjdk.jmh.annotations.Param;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Correctness gate for every JIT experiment's workload — the constants are
 * the shared fixture {@code ../fixtures/jit-pipeline-fixtures.json}; the
 * Rust AOT baseline pins exactly the same values from the identical input
 * stream. An optimization that changes any total invalidates the run by
 * construction.
 */
class JitKernelCorrectnessTest {

    @Test
    void inputStreamMatchesTheSharedFixture() {
        long[] amounts = PricingKernel.amounts();
        assertEquals(1024, amounts.length);
        assertEquals(328, amounts[0]);
        assertEquals(653, amounts[1]);
        assertEquals(744, amounts[2]);
    }

    @Test
    void everyPricerTotalMatchesTheSharedFixture() {
        long[] amounts = PricingKernel.amounts();
        assertEquals(50215100L, PricingKernel.total(new PricingKernel.BasicPricer(), amounts));
        assertEquals(45198710L, PricingKernel.total(new PricingKernel.DiscountPricer(), amounts));
        assertEquals(75319578L, PricingKernel.total(new PricingKernel.SurgePricer(), amounts));
    }

    @Test
    void mixedCallSiteTotalsMatchTheSharedFixture() {
        long[] amounts = PricingKernel.amounts();
        assertEquals(56392272L, PricingKernel.mixedTotal(new PricingKernel.Pricer[] {
            new PricingKernel.BasicPricer(), new PricingKernel.DiscountPricer(), new PricingKernel.SurgePricer(),
        }, amounts));
        assertEquals(47716500L, PricingKernel.mixedTotal(new PricingKernel.Pricer[] {
            new PricingKernel.BasicPricer(), new PricingKernel.DiscountPricer(),
        }, amounts));
    }

    @Test
    void escapeAnalysisKernelIsSemanticallyIdenticalToThePlainKernel() {
        long[] amounts = PricingKernel.amounts();
        assertEquals(
            PricingKernel.total(new PricingKernel.BasicPricer(), amounts),
            PricingKernel.totalWithHolder(new PricingKernel.BasicPricer(), amounts),
            "scalar replacement must never change the answer — that exactness is what makes the EA comparison valid");
    }

    @Test
    void steadyStateParametersMatchTheRunnerContract() throws NoSuchFieldException {
        Field shape = JitSteadyStateBenchmark.class.getField("callShape");
        assertEquals(List.of("mono", "bi", "mega", "escape"),
            List.of(shape.getAnnotation(Param.class).value()),
            "scripts/performance-lab/labs/jit-pipeline.conf selects these with -p callShape=<shape>");
    }

    @Test
    void trajectoryHarnessesAreNotJmhBenchmarks() {
        // Startup/warm-up/deopt are time series in dedicated harnesses —
        // JMH steady state and JVM warm-up are never one aggregate result.
        for (Class<?> harness : List.of(WarmupTrajectoryHarness.class, DeoptTrajectoryHarness.class)) {
            for (var method : harness.getDeclaredMethods()) {
                assertFalse(method.isAnnotationPresent(org.openjdk.jmh.annotations.Benchmark.class),
                    harness.getSimpleName() + " must not carry @Benchmark methods");
            }
        }
    }
}
