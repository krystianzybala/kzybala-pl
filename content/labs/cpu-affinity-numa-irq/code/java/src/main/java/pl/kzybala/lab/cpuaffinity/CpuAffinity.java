package pl.kzybala.lab.cpuaffinity;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Per-thread CPU pinning through Linux {@code sched_setaffinity}, via the FFM API (JDK 22+).
 * Same mechanism as {@code content/labs/thread-per-core/code/java/.../CpuAffinity.java}, copied
 * into this lab's own package rather than shared as a cross-lab dependency (each lab is an
 * independent Maven project). {@link #isSupported()} is false on non-Linux platforms (including
 * this repository's macOS development host) — callers must treat every pinned variant's
 * placement evidence as unavailable rather than fabricate a result when this is false.
 */
public final class CpuAffinity {

    private static final long CPU_SET_BYTES = 128;

    private static final MethodHandle SCHED_SETAFFINITY;
    private static final MethodHandle SCHED_GETCPU;
    private static final boolean SUPPORTED;

    static {
        MethodHandle setAffinity = null;
        MethodHandle getCpu = null;
        boolean supported = false;
        if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup libc = linker.defaultLookup();
                setAffinity = linker.downcallHandle(
                        libc.find("sched_setaffinity").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                getCpu = linker.downcallHandle(
                        libc.find("sched_getcpu").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT));
                supported = true;
            } catch (Throwable t) {
                supported = false;
            }
        }
        SCHED_SETAFFINITY = setAffinity;
        SCHED_GETCPU = getCpu;
        SUPPORTED = supported;
    }

    private CpuAffinity() {}

    public static boolean isSupported() {
        return SUPPORTED;
    }

    /** Best-effort pin: returns true if the calling thread was pinned to `cpu` and verified, false otherwise. Never throws. */
    public static boolean tryPinCurrentThread(int cpu) {
        if (!SUPPORTED) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment mask = arena.allocate(CPU_SET_BYTES);
            mask.set(ValueLayout.JAVA_LONG, (cpu / 64) * 8L, 1L << (cpu % 64));
            int rc = (int) SCHED_SETAFFINITY.invoke(0, CPU_SET_BYTES, mask);
            if (rc != 0) {
                return false;
            }
            Thread.yield();
            return currentCpu() == cpu;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int currentCpu() {
        if (!SUPPORTED) {
            return -1;
        }
        try {
            return (int) SCHED_GETCPU.invoke();
        } catch (Throwable t) {
            return -1;
        }
    }
}
