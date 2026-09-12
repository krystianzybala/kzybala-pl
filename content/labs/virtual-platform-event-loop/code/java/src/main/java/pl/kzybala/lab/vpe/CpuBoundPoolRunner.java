package pl.kzybala.lab.vpe;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** A fixed platform-thread pool sized to the number of available cores — for CPU-bound work, not high-fan-out blocking I/O. */
public final class CpuBoundPoolRunner implements VariantRunner {
    @Override
    public RunResult run(List<TaskSpec> tasks) throws Exception {
        AtomicLong sum = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        int poolSize = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Future<?>> futures = tasks.stream()
                    .<Future<?>>map(t -> pool.submit(() -> PlatformPerRequestRunner.runOne(t, sum, completed)))
                    .toList();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        return new RunResult(sum.get(), completed.get());
    }
}
