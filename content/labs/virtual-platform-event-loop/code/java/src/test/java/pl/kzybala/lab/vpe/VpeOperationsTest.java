package pl.kzybala.lab.vpe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VpeOperationsTest {

    static Stream<VariantRunner> variants() {
        return Stream.of(
                new PlatformPerRequestRunner(),
                new VirtualPerTaskRunner(),
                new FixedEventLoopRunner(),
                new CpuBoundPoolRunner(),
                new MixedRunner());
    }

    static Stream<VpeFixtures.Dataset> datasets() {
        return Stream.of(
                VpeFixtures.SIMULATED_SOCKET_WAIT,
                VpeFixtures.SHORT_CPU_STAGE,
                VpeFixtures.LONG_CPU_STAGE,
                VpeFixtures.LOCK_NATIVE_PINNING_CASE);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void everyVariantMatchesExpectedChecksumOnShortCpuStage(VariantRunner runner) throws Exception {
        assertVariantDataset(runner, VpeFixtures.SHORT_CPU_STAGE);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void everyVariantMatchesExpectedChecksumOnSimulatedSocketWait(VariantRunner runner) throws Exception {
        assertVariantDataset(runner, VpeFixtures.SIMULATED_SOCKET_WAIT);
    }

    @ParameterizedTest
    @MethodSource("variants")
    void everyVariantMatchesExpectedChecksumOnLockNativePinningCase(VariantRunner runner) throws Exception {
        assertVariantDataset(runner, VpeFixtures.LOCK_NATIVE_PINNING_CASE);
    }

    @Test
    void longCpuStageMatchesOnPlatformPerRequest() throws Exception {
        assertVariantDataset(new PlatformPerRequestRunner(), VpeFixtures.LONG_CPU_STAGE);
    }

    @Test
    void checksumIsPureAndDeterministic() {
        long a = Work.cpuChecksum(42, 1000);
        long b = Work.cpuChecksum(42, 1000);
        assertEquals(a, b);
    }

    private static void assertVariantDataset(VariantRunner runner, VpeFixtures.Dataset dataset) throws Exception {
        List<TaskSpec> tasks = VpeFixtures.tasksFor(dataset);
        RunResult result = runner.run(tasks);
        assertEquals(VpeFixtures.expectedChecksum(dataset), result.totalChecksum(),
                () -> runner.getClass().getSimpleName() + " checksum mismatch");
        assertEquals(VpeFixtures.TASK_COUNT, result.completedCount(),
                () -> runner.getClass().getSimpleName() + " completed-count mismatch");
    }
}
