package pl.kzybala.lab.vpe;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single event-loop thread. The blocking-wait component is modeled as a non-blocking
 * timer registration (the loop thread is never parked waiting for it) but the CPU-stage
 * checksum always executes on the one loop thread — this is the mechanism under test:
 * an event loop never blocks on I/O, but a long CPU stage still stalls every other task
 * queued behind it, because there is only ever one thread running application code.
 */
public final class FixedEventLoopRunner implements VariantRunner {
    @Override
    public RunResult run(List<TaskSpec> tasks) throws InterruptedException {
        AtomicLong sum = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(tasks.size());
        ScheduledExecutorService loop = Executors.newSingleThreadScheduledExecutor();
        try {
            for (TaskSpec t : tasks) {
                long delayNanos = Math.max(0, t.ioWaitNanos());
                loop.schedule(() -> {
                    long checksum = Work.cpuChecksum(t.id(), t.cpuIterations());
                    sum.addAndGet(checksum);
                    completed.incrementAndGet();
                    done.countDown();
                }, delayNanos, TimeUnit.NANOSECONDS);
            }
            done.await();
        } finally {
            loop.shutdown();
        }
        return new RunResult(sum.get(), completed.get());
    }
}
