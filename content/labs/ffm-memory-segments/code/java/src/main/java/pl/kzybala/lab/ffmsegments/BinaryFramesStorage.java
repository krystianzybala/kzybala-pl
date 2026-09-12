package pl.kzybala.lab.ffmsegments;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static pl.kzybala.lab.ffmsegments.FfmMemorySegmentsFixtures.BinaryFramesSource;
import static pl.kzybala.lab.ffmsegments.FfmMemorySegmentsFixtures.PAYLOAD_WORDS;

/**
 * The five storage variants for {@code binaryFrames} — a length-prefixed
 * protocol frame: {@code length}(4B) + {@code type}(4B) +
 * {@code payload}(6 longs, 48B) = 56-byte stride, every field at its own
 * natural alignment (payload starts at offset 8, itself 8-byte-aligned).
 */
public final class BinaryFramesStorage {

    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("length"),
        ValueLayout.JAVA_INT.withName("type"),
        MemoryLayout.sequenceLayout(PAYLOAD_WORDS, ValueLayout.JAVA_LONG).withName("payload"));
    private static final long STRIDE = LAYOUT.byteSize(); // 56
    private static final long LENGTH_OFF = LAYOUT.byteOffset(PathElement.groupElement("length"));
    private static final long TYPE_OFF = LAYOUT.byteOffset(PathElement.groupElement("type"));
    private static final long PAYLOAD_OFF = LAYOUT.byteOffset(PathElement.groupElement("payload"), PathElement.sequenceElement(0));
    private static final long HEADER_BYTES = 4096;

    private BinaryFramesStorage() {}

    private static long frameSum(int length, int type, long[] payload) {
        long sum = length + type;
        for (long v : payload) sum += v;
        return sum;
    }

    // ---- heapPrimitiveArray ----

    public static RecordStorage heapPrimitiveArray(BinaryFramesSource s) {
        int[] type = s.type.clone();
        long[][] payload = new long[s.payload.length][];
        for (int i = 0; i < s.payload.length; i++) payload[i] = s.payload[i].clone();
        int n = type.length;
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                for (int i = 0; i < n; i++) sum += frameSum(FfmMemorySegmentsFixtures.FRAME_LENGTH_FIELD, type[i], payload[i]);
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) sum += frameSum(FfmMemorySegmentsFixtures.FRAME_LENGTH_FIELD, type[idx], payload[idx]);
                return sum;
            }

            @Override
            public void close() {}
        };
    }

    // ---- off-heap segment mechanics, shared by confined/shared/sliced/copied ----

    private static MemorySegment writeSegment(Arena arena, BinaryFramesSource s, long baseOffset, long totalBytes) {
        MemorySegment segment = arena.allocate(totalBytes, LAYOUT.byteAlignment());
        int n = s.type.length;
        for (int i = 0; i < n; i++) {
            long base = baseOffset + i * STRIDE;
            segment.set(ValueLayout.JAVA_INT, base + LENGTH_OFF, FfmMemorySegmentsFixtures.FRAME_LENGTH_FIELD);
            segment.set(ValueLayout.JAVA_INT, base + TYPE_OFF, s.type[i]);
            for (int w = 0; w < PAYLOAD_WORDS; w++) {
                segment.set(ValueLayout.JAVA_LONG, base + PAYLOAD_OFF + w * 8L, s.payload[i][w]);
            }
        }
        return segment;
    }

    private static long readFrameSum(MemorySegment segment, long base) {
        int length = segment.get(ValueLayout.JAVA_INT, base + LENGTH_OFF);
        int type = segment.get(ValueLayout.JAVA_INT, base + TYPE_OFF);
        long sum = length + type;
        for (int w = 0; w < PAYLOAD_WORDS; w++) {
            sum += segment.get(ValueLayout.JAVA_LONG, base + PAYLOAD_OFF + w * 8L);
        }
        return sum;
    }

    private static RecordStorage segmentStorage(Arena arena, MemorySegment segment, long baseOffset, int n) {
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                for (int i = 0; i < n; i++) sum += readFrameSum(segment, baseOffset + i * STRIDE);
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) sum += readFrameSum(segment, baseOffset + (long) idx * STRIDE);
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }

    public static RecordStorage confinedSegment(BinaryFramesSource s) {
        Arena arena = Arena.ofConfined();
        MemorySegment segment = writeSegment(arena, s, 0, STRIDE * s.type.length);
        return segmentStorage(arena, segment, 0, s.type.length);
    }

    public static RecordStorage sharedSegment(BinaryFramesSource s) {
        Arena arena = Arena.ofShared();
        MemorySegment segment = writeSegment(arena, s, 0, STRIDE * s.type.length);
        return segmentStorage(arena, segment, 0, s.type.length);
    }

    public static RecordStorage slicedView(BinaryFramesSource s) {
        Arena arena = Arena.ofShared();
        long payloadBytes = STRIDE * s.type.length;
        MemorySegment backing = writeSegment(arena, s, HEADER_BYTES, HEADER_BYTES + payloadBytes);
        MemorySegment slice = backing.asSlice(HEADER_BYTES, payloadBytes);
        return segmentStorage(arena, slice, 0, s.type.length);
    }

    public static RecordStorage copiedBoundaryCrossing(BinaryFramesSource s) {
        Arena arena = Arena.ofShared();
        int n = s.type.length;
        MemorySegment offHeap = writeSegment(arena, s, 0, STRIDE * n);
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                int[] type = new int[n];
                long[][] payload = new long[n][PAYLOAD_WORDS];
                for (int i = 0; i < n; i++) {
                    long base = i * STRIDE;
                    type[i] = offHeap.get(ValueLayout.JAVA_INT, base + TYPE_OFF);
                    for (int w = 0; w < PAYLOAD_WORDS; w++) {
                        payload[i][w] = offHeap.get(ValueLayout.JAVA_LONG, base + PAYLOAD_OFF + w * 8L);
                    }
                }
                long sum = 0;
                for (int i = 0; i < n; i++) sum += frameSum(FfmMemorySegmentsFixtures.FRAME_LENGTH_FIELD, type[i], payload[i]);
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                int[] type = new int[n];
                long[][] payload = new long[n][PAYLOAD_WORDS];
                for (int i = 0; i < n; i++) {
                    long base = i * STRIDE;
                    type[i] = offHeap.get(ValueLayout.JAVA_INT, base + TYPE_OFF);
                    for (int w = 0; w < PAYLOAD_WORDS; w++) {
                        payload[i][w] = offHeap.get(ValueLayout.JAVA_LONG, base + PAYLOAD_OFF + w * 8L);
                    }
                }
                long sum = 0;
                for (int idx : indices) sum += frameSum(FfmMemorySegmentsFixtures.FRAME_LENGTH_FIELD, type[idx], payload[idx]);
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }
}
