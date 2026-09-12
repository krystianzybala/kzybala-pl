package pl.kzybala.lab.vpe;

/** One unit of work: an id (checksum seed), a simulated blocking-wait duration and a CPU-stage iteration count. */
public record TaskSpec(int id, long ioWaitNanos, int cpuIterations, boolean synchronizedWrap) {
    public TaskSpec(int id, long ioWaitNanos, int cpuIterations) {
        this(id, ioWaitNanos, cpuIterations, false);
    }
}
