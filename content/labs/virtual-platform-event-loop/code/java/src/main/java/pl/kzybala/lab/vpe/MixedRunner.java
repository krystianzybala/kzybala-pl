package pl.kzybala.lab.vpe;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual threads absorb the blocking-wait fan-out, but every CPU stage is handed off to a
 * small, fixed, core-sized pool — the "isolate CPU-bound work" pattern: cheap, unbounded
 * virtual threads for blocking I/O, a bounded pool for the work that actually needs a core.
 */
public final class MixedRunner implements VariantRunner {
    @Override
    public RunResult run(List<TaskSpec> tasks) throws Exception {
        AtomicLong sum = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        int cpuThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService cpuPool = Executors.newFixedThreadPool(cpuThreads);
        try {
            try (ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = tasks.stream()
                        .<Future<?>>map(t -> ioExecutor.submit(() -> {
                            Work.blockingWait(t.ioWaitNanos());
                            Future<Long> cpuFuture = cpuPool.submit(() -> Work.cpuChecksum(t.id(), t.cpuIterations()));
                            try {
                                sum.addAndGet(cpuFuture.get());
                                completed.incrementAndGet();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }))
                        .toList();
                for (Future<?> f : futures) {
                    f.get();
                }
            }
        } finally {
            cpuPool.shutdown();
        }
        return new RunResult(sum.get(), completed.get());
    }
}
