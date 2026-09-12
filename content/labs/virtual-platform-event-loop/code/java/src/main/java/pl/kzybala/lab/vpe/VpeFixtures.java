package pl.kzybala.lab.vpe;

import java.util.ArrayList;
import java.util.List;

/** Hardcoded mirror of code/fixtures/virtual-platform-event-loop-fixtures.json — kept in sync manually, asserted identical to Rust's constants by both languages' correctness tests. */
public final class VpeFixtures {
    public static final int TASK_COUNT = 200;

    public record Dataset(long ioWaitNanos, int cpuIterations, boolean synchronizedWrap) {}

    public static final Dataset SIMULATED_SOCKET_WAIT = new Dataset(2_000_000L, 100, false);
    public static final Dataset SHORT_CPU_STAGE = new Dataset(0L, 2_000, false);
    public static final Dataset LONG_CPU_STAGE = new Dataset(0L, 200_000, false);
    public static final Dataset LOCK_NATIVE_PINNING_CASE = new Dataset(1_000_000L, 500, true);

    private VpeFixtures() {}

    public static List<TaskSpec> tasksFor(Dataset dataset) {
        List<TaskSpec> tasks = new ArrayList<>(TASK_COUNT);
        for (int id = 0; id < TASK_COUNT; id++) {
            tasks.add(new TaskSpec(id, dataset.ioWaitNanos(), dataset.cpuIterations(), dataset.synchronizedWrap()));
        }
        return tasks;
    }

    public static long expectedChecksum(Dataset dataset) {
        long total = 0L;
        for (int id = 0; id < TASK_COUNT; id++) {
            total += Work.cpuChecksum(id, dataset.cpuIterations());
        }
        return total;
    }
}
