package pl.kzybala.lab.harnesstraps;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-thread CPU pinning through Linux {@code sched_setaffinity}, called
 * via the FFM API (JDK 22+ final). Process-level {@code taskset} confines
 * the whole JVM but cannot guarantee which of the allowed CPUs a specific
 * benchmark worker runs on — this class pins the <em>calling thread</em>
 * to exactly one CPU, verifies the placement, and exposes the kernel's
 * own per-thread migration counter so a run can prove the worker never
 * moved during measurement.
 *
 * <p>All methods throw on failure rather than degrade silently: a
 * publication run must abort when pinning cannot be established
 * (docs/linux-evidence-runner.md). On non-Linux platforms
 * {@link #isSupported()} is false and callers must not attempt to pin.
 */
public final class CpuAffinity {

    private static final long CPU_SET_BYTES = 128; // cpu_set_t: 1024 bits

    private static final MethodHandle SCHED_SETAFFINITY;
    private static final MethodHandle SCHED_GETAFFINITY;
    private static final MethodHandle SCHED_GETCPU;
    private static final MethodHandle GETTID;
    private static final boolean SUPPORTED;

    static {
        MethodHandle setAffinity = null;
        MethodHandle getAffinity = null;
        MethodHandle getCpu = null;
        MethodHandle getTid = null;
        boolean supported = false;
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup libc = linker.defaultLookup();
                setAffinity = linker.downcallHandle(
                    libc.find("sched_setaffinity").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                getAffinity = linker.downcallHandle(
                    libc.find("sched_getaffinity").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                getCpu = linker.downcallHandle(
                    libc.find("sched_getcpu").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                // glibc >= 2.30; absence is recorded, not fatal (tid falls back to -1)
                getTid = libc.find("gettid")
                    .map(addr -> linker.downcallHandle(addr, FunctionDescriptor.of(ValueLayout.JAVA_INT)))
                    .orElse(null);
                supported = true;
            } catch (Throwable t) {
                supported = false;
            }
        }
        SCHED_SETAFFINITY = setAffinity;
        SCHED_GETAFFINITY = getAffinity;
        SCHED_GETCPU = getCpu;
        GETTID = getTid;
        SUPPORTED = supported;
    }

    private CpuAffinity() {}

    public static boolean isSupported() {
        return SUPPORTED;
    }

    /**
     * Pins the calling thread to exactly {@code cpu} and verifies the
     * kernel placed it there. pid 0 = the calling thread, per
     * sched_setaffinity(2).
     */
    public static void pinCurrentThread(int cpu) {
        if (!SUPPORTED) {
            throw new IllegalStateException("CPU affinity is not supported on this platform — publication pinning must not proceed");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_BYTES);
            mask.set(ValueLayout.JAVA_LONG, (cpu / 64) * 8L, 1L << (cpu % 64));
            int rc = (int) SCHED_SETAFFINITY.invoke(0, CPU_SET_BYTES, mask);
            if (rc != 0) {
                throw new IllegalStateException("sched_setaffinity(cpu=" + cpu + ") failed (rc=" + rc + ")");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("sched_setaffinity invocation failed", t);
        }
        // The mask change takes effect immediately, but verify from the
        // kernel's point of view rather than trusting the return code.
        Thread.yield();
        int observed = currentCpu();
        if (observed != cpu) {
            throw new IllegalStateException("pinned to CPU " + cpu + " but running on CPU " + observed + " after sched_setaffinity");
        }
    }

    public static int currentCpu() {
        try {
            return (int) SCHED_GETCPU.invoke();
        } catch (Throwable t) {
            throw new IllegalStateException("sched_getcpu failed", t);
        }
    }

    /** The calling thread's affinity mask as a hex string (low word first). */
    public static String currentAffinityMaskHex() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_BYTES);
            int rc = (int) SCHED_GETAFFINITY.invoke(0, CPU_SET_BYTES, mask);
            if (rc != 0) return "unavailable";
            StringBuilder sb = new StringBuilder();
            for (int word = 0; word < 2; word++) { // 128 CPUs is plenty for this host class
                sb.append(String.format("%016x", mask.get(ValueLayout.JAVA_LONG, word * 8L)));
                if (word == 0) sb.append(":");
            }
            return sb.toString();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /** Native thread id (gettid), or -1 when unavailable. */
    public static int nativeThreadId() {
        if (GETTID == null) return -1;
        try {
            return (int) GETTID.invoke();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The kernel's own per-thread migration counter
     * ({@code /proc/self/task/<tid>/sched}, {@code se.nr_migrations}) —
     * the ground truth for "did this worker move", independent of any
     * process-level aggregate. Returns -1 when unavailable.
     */
    public static long threadMigrationCount() {
        int tid = nativeThreadId();
        if (tid <= 0) return -1;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/task/" + tid + "/sched"))) {
                if (line.startsWith("se.nr_migrations")) {
                    return Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }
}
