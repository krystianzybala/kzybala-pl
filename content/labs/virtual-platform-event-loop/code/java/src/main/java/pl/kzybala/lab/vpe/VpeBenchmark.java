package pl.kzybala.lab.vpe;

import org.openjdk.jmh.annotations.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class VpeBenchmark {

    private List<TaskSpec> simulatedSocketWait;
    private List<TaskSpec> shortCpuStage;
    private List<TaskSpec> longCpuStage;

    @Setup(Level.Trial)
    public void setup() {
        simulatedSocketWait = VpeFixtures.tasksFor(VpeFixtures.SIMULATED_SOCKET_WAIT);
        shortCpuStage = VpeFixtures.tasksFor(VpeFixtures.SHORT_CPU_STAGE);
        longCpuStage = VpeFixtures.tasksFor(VpeFixtures.LONG_CPU_STAGE);
    }

    @Benchmark
    public RunResult platformPerRequest_socketWait() throws Exception {
        return new PlatformPerRequestRunner().run(simulatedSocketWait);
    }

    @Benchmark
    public RunResult virtualPerTask_socketWait() throws Exception {
        return new VirtualPerTaskRunner().run(simulatedSocketWait);
    }

    @Benchmark
    public RunResult fixedEventLoop_socketWait() throws Exception {
        return new FixedEventLoopRunner().run(simulatedSocketWait);
    }

    @Benchmark
    public RunResult cpuBoundPool_shortCpu() throws Exception {
        return new CpuBoundPoolRunner().run(shortCpuStage);
    }

    @Benchmark
    public RunResult fixedEventLoop_longCpu() throws Exception {
        return new FixedEventLoopRunner().run(longCpuStage);
    }

    @Benchmark
    public RunResult mixed_socketWait() throws Exception {
        return new MixedRunner().run(simulatedSocketWait);
    }
}
