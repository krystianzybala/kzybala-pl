package pl.kzybala.lab.safepoints;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * A genuine FFM downcall to POSIX {@code usleep(useconds_t usec)} —
 * while this thread is inside the native call, it is not executing Java
 * bytecode and cannot poll for a safepoint; HotSpot instead marks it "in
 * native" and does not wait for it during safepoint synchronization
 * (java.md explains why this means a native call does NOT add to TTSP,
 * a common misconception this lab corrects with real evidence).
 */
public final class NativeSleep {

    private static final MethodHandle USLEEP;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup libc = linker.defaultLookup();
        USLEEP = linker.downcallHandle(
            libc.find("usleep").orElseThrow(() -> new IllegalStateException("usleep not found")),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }

    private NativeSleep() {}

    /** Return code is intentionally unused: usleep can be interrupted, and this
     * lab only needs "this thread spent time in native code", not an exact duration. */
    public static void sleepMicros(int micros) {
        try {
            int rc = (int) USLEEP.invokeExact(micros);
        } catch (Throwable t) {
            throw new IllegalStateException("usleep invocation failed", t);
        }
    }
}
