package pl.kzybala.lab.ffminterop;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * Loads the compiled Rust {@code cdylib} and exposes downcall handles for
 * its narrow, stable C ABI — the same {@code Linker}/{@code SymbolLookup}/
 * {@code FunctionDescriptor} pattern this repository already uses for
 * libc downcalls (see {@code CpuAffinity}, {@code NativeSleep} in other
 * labs), applied here to a library THIS lab's own build produces instead
 * of the system's libc.
 *
 * <p>Ensures the native library is built by shelling out to
 * {@code cargo build --release} if the expected artifact is not already
 * present, so {@code mvn test}/the JMH benchmark work without a separate
 * manual build step — the same self-sufficiency this repository's other
 * cross-language labs expect from a fresh checkout.
 */
public final class RustLibrary {

    private static final MethodHandle TRANSFORM_SCALAR;
    private static final MethodHandle TRANSFORM_BATCH;
    private static final MethodHandle VALIDATE_RECORDS;
    private static final MethodHandle TRANSFORM_WITH_CALLBACK;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena LIBRARY_ARENA = Arena.ofShared();

    static {
        Path libraryPath = ensureBuiltAndResolve();
        SymbolLookup rust = SymbolLookup.libraryLookup(libraryPath, LIBRARY_ARENA);

        TRANSFORM_SCALAR = LINKER.downcallHandle(
                rust.find("ffm_transform_scalar").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));

        TRANSFORM_BATCH = LINKER.downcallHandle(
                rust.find("ffm_transform_batch").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

        VALIDATE_RECORDS = LINKER.downcallHandle(
                rust.find("ffm_validate_records").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

        TRANSFORM_WITH_CALLBACK = LINKER.downcallHandle(
                rust.find("ffm_transform_with_callback").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
    }

    private RustLibrary() {
    }

    public static double transformScalar(double x) {
        try {
            return (double) TRANSFORM_SCALAR.invokeExact(x);
        } catch (Throwable t) {
            throw new IllegalStateException("ffm_transform_scalar downcall failed", t);
        }
    }

    /** {@code inSegment}/{@code outSegment} must each be exactly {@code count} doubles long. */
    public static void transformBatch(java.lang.foreign.MemorySegment inSegment,
                                       java.lang.foreign.MemorySegment outSegment, long count) {
        try {
            TRANSFORM_BATCH.invokeExact(inSegment, count, outSegment);
        } catch (Throwable t) {
            throw new IllegalStateException("ffm_transform_batch downcall failed", t);
        }
    }

    /** Returns the count of invalid fixed records in {@code segment}, or -1 if length is misaligned. */
    public static long validateRecords(java.lang.foreign.MemorySegment segment, long byteLength) {
        try {
            return (long) VALIDATE_RECORDS.invokeExact(segment, byteLength);
        } catch (Throwable t) {
            throw new IllegalStateException("ffm_validate_records downcall failed", t);
        }
    }

    /**
     * Calls into Rust, which then calls back into Java once per element via
     * the upcall stub {@code callbackStub} (built by {@link #buildCallbackStub}).
     */
    public static void transformWithCallback(java.lang.foreign.MemorySegment inSegment, long count,
                                              java.lang.foreign.MemorySegment callbackStub) {
        try {
            TRANSFORM_WITH_CALLBACK.invokeExact(inSegment, count, callbackStub);
        } catch (Throwable t) {
            throw new IllegalStateException("ffm_transform_with_callback downcall failed", t);
        }
    }

    /**
     * Builds a native function pointer (an "upcall stub") over a Java
     * {@code MethodHandle} matching {@code void callback(double)} — this
     * is what makes Rust-to-Java upcalls possible: Rust receives an
     * ordinary C function pointer and has no idea it points into the JVM.
     */
    public static java.lang.foreign.MemorySegment buildCallbackStub(MethodHandle javaCallback, Arena arena) {
        FunctionDescriptor descriptor = FunctionDescriptor.ofVoid(ValueLayout.JAVA_DOUBLE);
        return LINKER.upcallStub(javaCallback, descriptor, arena);
    }

    private static Path ensureBuiltAndResolve() {
        Path rustDir = resolveRustDir();
        String libName = switch (osFamily()) {
            case "mac" -> "libffm_interop_lab.dylib";
            case "linux" -> "libffm_interop_lab.so";
            default -> throw new IllegalStateException("unsupported OS for this lab's native library: " + System.getProperty("os.name"));
        };
        Path libPath = rustDir.resolve("target/release").resolve(libName);
        if (!java.nio.file.Files.exists(libPath)) {
            buildRustLibrary(rustDir);
        }
        if (!java.nio.file.Files.exists(libPath)) {
            throw new IllegalStateException("expected native library not found after cargo build: " + libPath);
        }
        return libPath;
    }

    private static void buildRustLibrary(Path rustDir) {
        try {
            Process process = new ProcessBuilder("cargo", "build", "--release")
                    .directory(rustDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("cargo build --release failed (exit " + exit + "):\n" + output);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("failed to run cargo build --release in " + rustDir, e);
        }
    }

    private static Path resolveRustDir() {
        // This class's own location, walked up to code/java/, then across to code/rust/.
        Path here = Path.of("").toAbsolutePath();
        // Works whether invoked from code/java/ (mvn test) or the repo root.
        Path candidate = here.resolve("../rust").normalize();
        if (java.nio.file.Files.exists(candidate.resolve("Cargo.toml"))) {
            return candidate;
        }
        candidate = here.resolve("content/labs/java-rust-ffm-interop/code/rust").normalize();
        if (java.nio.file.Files.exists(candidate.resolve("Cargo.toml"))) {
            return candidate;
        }
        throw new IllegalStateException("could not locate the Rust crate directory (Cargo.toml) relative to " + here);
    }

    private static String osFamily() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("mac") || name.contains("darwin")) {
            return "mac";
        }
        if (name.contains("linux")) {
            return "linux";
        }
        return "other";
    }
}
