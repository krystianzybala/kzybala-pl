package pl.kzybala.lab.tpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TpcOperationsTest {

    @Test
    void sharedMapReachesExactUniformCounts() throws InterruptedException {
        TpcResult r = SharedMapKernel.run(WorkloadPlan.uniform());
        assertTrue(r.isCorrectUniform());
    }

    @Test
    void sharedMapReachesExactSkewedCounts() throws InterruptedException {
        TpcResult r = SharedMapKernel.run(WorkloadPlan.skewed());
        assertTrue(r.isCorrectSkewed());
    }

    @Test
    void mutexShardsReachesExactUniformCounts() throws InterruptedException {
        TpcResult r = MutexShardsKernel.run(WorkloadPlan.uniform());
        assertTrue(r.isCorrectUniform());
    }

    @Test
    void mutexShardsReachesExactSkewedCounts() throws InterruptedException {
        TpcResult r = MutexShardsKernel.run(WorkloadPlan.skewed());
        assertTrue(r.isCorrectSkewed());
    }

    @Test
    void singleWriterReachesExactUniformCounts() throws InterruptedException {
        TpcResult r = SingleWriterKernel.run(WorkloadPlan.uniform());
        assertTrue(r.isCorrectUniform());
    }

    @Test
    void singleWriterReachesExactSkewedCounts() throws InterruptedException {
        TpcResult r = SingleWriterKernel.run(WorkloadPlan.skewed());
        assertTrue(r.isCorrectSkewed());
    }

    @Test
    void rebalanceSimulationReachesExactUniformCountsAcrossBothPhases() throws InterruptedException {
        TpcResult r = RebalanceSimulationKernel.run();
        assertTrue(r.isCorrectUniform());
    }
}
