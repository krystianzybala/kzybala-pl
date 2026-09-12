package pl.kzybala.lab.ffmsegments;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The five storage variants for {@code largeNumericBuffers} (one long
 * field, 8-byte stride, N = 5,000,000 — 40MB, deliberately larger than
 * typical L2/L3 cache so storage-location access cost is actually
 * visible rather than dominated by cache residency alone).
 */
public final class LargeNumericBufferStorage {

    private static final long STRIDE = 8;
    private static final long HEADER_BYTES = 4096;

    private LargeNumericBufferStorage() {}

    public static RecordStorage heapPrimitiveArray(long[] value) {
        long[] copy = value.clone();
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                for (long v : copy) sum += v;
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) sum += copy[idx];
                return sum;
            }

            @Override
            public void close() {}
        };
    }

    private static MemorySegment writeSegment(Arena arena, long[] value, long baseOffset, long totalBytes) {
        MemorySegment segment = arena.allocate(totalBytes, 8);
        for (int i = 0; i < value.length; i++) {
            segment.set(ValueLayout.JAVA_LONG, baseOffset + i * STRIDE, value[i]);
        }
        return segment;
    }

    private static RecordStorage segmentStorage(Arena arena, MemorySegment segment, long baseOffset, int n) {
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                for (int i = 0; i < n; i++) {
                    sum += segment.get(ValueLayout.JAVA_LONG, baseOffset + i * STRIDE);
                }
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) sum += segment.get(ValueLayout.JAVA_LONG, baseOffset + (long) idx * STRIDE);
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }

    public static RecordStorage confinedSegment(long[] value) {
        Arena arena = Arena.ofConfined();
        MemorySegment segment = writeSegment(arena, value, 0, STRIDE * value.length);
        return segmentStorage(arena, segment, 0, value.length);
    }

    public static RecordStorage sharedSegment(long[] value) {
        Arena arena = Arena.ofShared();
        MemorySegment segment = writeSegment(arena, value, 0, STRIDE * value.length);
        return segmentStorage(arena, segment, 0, value.length);
    }

    public static RecordStorage slicedView(long[] value) {
        Arena arena = Arena.ofShared();
        long payloadBytes = STRIDE * value.length;
        MemorySegment backing = writeSegment(arena, value, HEADER_BYTES, HEADER_BYTES + payloadBytes);
        MemorySegment slice = backing.asSlice(HEADER_BYTES, payloadBytes);
        return segmentStorage(arena, slice, 0, value.length);
    }

    public static RecordStorage copiedBoundaryCrossing(long[] value) {
        Arena arena = Arena.ofShared();
        int n = value.length;
        MemorySegment offHeap = writeSegment(arena, value, 0, STRIDE * n);
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long[] copy = new long[n];
                for (int i = 0; i < n; i++) copy[i] = offHeap.get(ValueLayout.JAVA_LONG, i * STRIDE);
                long sum = 0;
                for (long v : copy) sum += v;
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long[] copy = new long[n];
                for (int i = 0; i < n; i++) copy[i] = offHeap.get(ValueLayout.JAVA_LONG, i * STRIDE);
                long sum = 0;
                for (int idx : indices) sum += copy[idx];
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }
}
