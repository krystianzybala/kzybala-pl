package pl.kzybala.lab.ffminterop;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * The six variants, all performing (or built around) the same
 * deterministic transform {@code x -> x*2.0+1.0}, matching
 * {@code transform_scalar_pure} on the Rust side exactly.
 */
public final class FfmInteropOps {

    private FfmInteropOps() {
    }

    public static double transformScalarPureJava(double x) {
        return x * 2.0 + 1.0;
    }

    /** Variant: pure Java baseline — no FFI at all. */
    public static double[] pureJavaBaseline(double[] input) {
        double[] out = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = transformScalarPureJava(input[i]);
        }
        return out;
    }

    /** Variant: scalar downcall per item — one FFI crossing per element. */
    public static double[] scalarDowncall(double[] input) {
        double[] out = new double[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = RustLibrary.transformScalar(input[i]);
        }
        return out;
    }

    /**
     * Variant: batched downcall — ONE FFI crossing for the whole array, but
     * Java must first COPY its heap {@code double[]} into an off-heap
     * segment (and copy the result back out) because the array itself is
     * not natively addressable — the "silently copying buffers" cost this
     * variant makes explicit rather than hides.
     */
    public static double[] batchedDowncall(double[] input) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment inSeg = arena.allocate((long) input.length * Double.BYTES);
            MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_DOUBLE, 0, input.length);
            MemorySegment outSeg = arena.allocate((long) input.length * Double.BYTES);
            RustLibrary.transformBatch(inSeg, outSeg, input.length);
            double[] out = new double[input.length];
            MemorySegment.copy(outSeg, ValueLayout.JAVA_DOUBLE, 0, out, 0, input.length);
            return out;
        }
    }

    /**
     * Variant: zero-copy buffer downcall — the caller already holds the
     * data as a {@link MemorySegment} (never materialized as a Java
     * {@code double[]} at all), so there is no copy at either side of this
     * one FFI crossing. Transforms in place.
     */
    public static void zeroCopyDowncall(MemorySegment segment, long count) {
        RustLibrary.transformBatch(segment, segment, count);
    }

    /** Variant support: fixed-record validation downcall — one FFI crossing for the whole buffer. */
    public static long validateRecords(byte[] recordBytes) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(recordBytes.length);
            MemorySegment.copy(recordBytes, 0, segment, ValueLayout.JAVA_BYTE, 0, recordBytes.length);
            return RustLibrary.validateRecords(segment, recordBytes.length);
        }
    }

    /**
     * Variant: Rust-to-Java upcall — Rust iterates the array and calls
     * back into this JVM once per element via a native function pointer
     * built from {@code sink}'s {@code onValue(double)} method.
     */
    public static List<Double> upcallTransform(double[] input) {
        CallbackSink sink = new CallbackSink();
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle bound;
            try {
                bound = MethodHandles.lookup().findVirtual(
                                CallbackSink.class, "onValue", MethodType.methodType(void.class, double.class))
                        .bindTo(sink);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("failed to bind callback method handle", e);
            }
            MemorySegment callbackStub = RustLibrary.buildCallbackStub(bound, arena);

            MemorySegment inSeg = arena.allocate((long) input.length * Double.BYTES);
            MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_DOUBLE, 0, input.length);

            RustLibrary.transformWithCallback(inSeg, input.length, callbackStub);
        }
        return sink.values;
    }

    /** Receives one upcall per transformed element, in order. */
    public static final class CallbackSink {
        private final List<Double> values = new ArrayList<>();

        public void onValue(double v) {
            values.add(v);
        }
    }

    /** Same as {@link #upcallTransform} but reports through a {@link DoubleConsumer} instead of collecting a list — used by the benchmark to avoid boxing/list-allocation overhead confounding the callback-cost measurement. */
    public static void upcallTransform(double[] input, DoubleConsumer consumer) {
        try (Arena arena = Arena.ofConfined()) {
            MethodHandle bound;
            try {
                bound = MethodHandles.lookup().findVirtual(
                                DoubleConsumer.class, "accept", MethodType.methodType(void.class, double.class))
                        .bindTo(consumer);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("failed to bind callback method handle", e);
            }
            MemorySegment callbackStub = RustLibrary.buildCallbackStub(bound, arena);

            MemorySegment inSeg = arena.allocate((long) input.length * Double.BYTES);
            MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_DOUBLE, 0, input.length);

            RustLibrary.transformWithCallback(inSeg, input.length, callbackStub);
        }
    }
}
