package pl.kzybala.lab.vpe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** One brand-new platform (OS) thread per task, no pooling — the classic thread-per-request baseline. */
public final class PlatformPerRequestRunner implements VariantRunner {
    @Override
    public RunResult run(List<TaskSpec> tasks) throws InterruptedException {
        AtomicLong sum = new AtomicLong();
        AtomicInteger completed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>(tasks.size());
        for (TaskSpec t : tasks) {
            Thread th = new Thread(() -> runOne(t, sum, completed));
            th.start();
            threads.add(th);
        }
        for (Thread th : threads) {
            th.join();
        }
        return new RunResult(sum.get(), completed.get());
    }

    static void runOne(TaskSpec t, AtomicLong sum, AtomicInteger completed) {
        long checksum;
        if (t.synchronizedWrap()) {
            checksum = Work.runLocked(t);
        } else {
            Work.blockingWait(t.ioWaitNanos());
            checksum = Work.cpuChecksum(t.id(), t.cpuIterations());
        }
        sum.addAndGet(checksum);
        completed.incrementAndGet();
    }
}
