package pl.kzybala.lab.cachehierarchy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Pins one benchmark worker thread to one CPU and proves it: intended vs
 * observed CPU, native tid, affinity mask, and the kernel's per-thread
 * migration counter sampled at pin time and re-checked at teardown. A
 * worker that fails to pin, lands elsewhere, or migrated during the trial
 * throws — failing the fork, which fails the run (publication policy:
 * worker migrations are blocking; docs/linux-evidence-runner.md).
 *
 * <p>Configuration comes from immutable JVM properties set by the evidence
 * runner: {@code -Dplab.cpuA=<n> -Dplab.cpuB=<n>
 * -Dplab.placementDir=<dir>}. When {@code plab.cpuA} is absent the
 * benchmark runs unpinned (development mode) and no placement artifact is
 * written — the import gate treats a missing placement artifact as
 * non-publishable, so development runs can never masquerade as pinned.
 */
final class WorkerPin {

    static final Integer CPU_A = intProperty("plab.cpuA");
    static final Integer CPU_B = intProperty("plab.cpuB");
    private static final String PLACEMENT_DIR = System.getProperty("plab.placementDir");

    private final String role;
    private final int intendedCpu;
    private int tid;
    private int observedAfterPin;
    private String maskHex;
    private long migrationsAtPin;

    private WorkerPin(String role, int intendedCpu) {
        this.role = role;
        this.intendedCpu = intendedCpu;
    }

    private static Integer intProperty(String name) {
        String value = System.getProperty(name);
        return value == null ? null : Integer.valueOf(value);
    }

    static boolean pinningRequested() {
        return CPU_A != null;
    }

    /** Pin the calling thread; must run in setup, never in a measured method. */
    static WorkerPin establish(String role, int cpu) {
        WorkerPin pin = new WorkerPin(role, cpu);
        CpuAffinity.pinCurrentThread(cpu); // throws on failure — aborts the run
        pin.tid = CpuAffinity.nativeThreadId();
        pin.observedAfterPin = CpuAffinity.currentCpu();
        pin.maskHex = CpuAffinity.currentAffinityMaskHex();
        pin.migrationsAtPin = CpuAffinity.threadMigrationCount();
        return pin;
    }

    /** Verify at teardown and persist the placement evidence. */
    void verifyAndRecord() {
        int observedAtEnd = CpuAffinity.currentCpu();
        long migrationsAtEnd = CpuAffinity.threadMigrationCount();
        long delta = (migrationsAtPin < 0 || migrationsAtEnd < 0) ? -1 : migrationsAtEnd - migrationsAtPin;
        boolean stayed = observedAtEnd == intendedCpu && delta == 0;
        record(observedAtEnd, migrationsAtEnd, delta);
        if (!stayed) {
            throw new IllegalStateException(
                "worker " + role + " placement violated: intended CPU " + intendedCpu
                    + ", at teardown on CPU " + observedAtEnd + ", migrations during trial: " + delta
                    + " — run is invalid for publication");
        }
    }

    private void record(int observedAtEnd, long migrationsAtEnd, long delta) {
        if (PLACEMENT_DIR == null) return;
        String json = "{\n"
            + "  \"role\": \"" + role + "\",\n"
            + "  \"threadName\": \"" + Thread.currentThread().getName() + "\",\n"
            + "  \"nativeThreadId\": " + tid + ",\n"
            + "  \"intendedCpu\": " + intendedCpu + ",\n"
            + "  \"observedCpuAfterPin\": " + observedAfterPin + ",\n"
            + "  \"observedCpuAtTeardown\": " + observedAtEnd + ",\n"
            + "  \"affinityMask\": \"" + maskHex + "\",\n"
            + "  \"migrationsAtPin\": " + migrationsAtPin + ",\n"
            + "  \"migrationsAtTeardown\": " + migrationsAtEnd + ",\n"
            + "  \"migrationsDuringTrial\": " + delta + ",\n"
            + "  \"pinned\": true\n"
            + "}\n";
        Path out = Path.of(PLACEMENT_DIR,
            "worker-placement-" + ProcessHandle.current().pid() + "-" + role + ".json");
        try {
            Files.writeString(out, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write worker placement artifact " + out, e);
        }
    }
}
