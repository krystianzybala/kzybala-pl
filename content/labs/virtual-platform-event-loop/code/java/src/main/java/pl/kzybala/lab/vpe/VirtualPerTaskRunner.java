package pl.kzybala.lab.vpe;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One virtual thread per task via the JDK 21+ structured executor. */
public final class VirtualPerTaskRunner implements VariantRunner {
    @Override
    public RunResult run(List<TaskSpec> tasks) throws Exception {
        AtomicLong sum = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = tasks.stream()
                    .<Future<?>>map(t -> executor.submit(() -> PlatformPerRequestRunner.runOne(t, sum, completed)))
                    .toList();
            for (Future<?> f : futures) {
                f.get();
            }
        }
        return new RunResult(sum.get(), completed.get());
    }
}
