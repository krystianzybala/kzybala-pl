package pl.kzybala.lab.ffmsegments;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static pl.kzybala.lab.ffmsegments.FfmMemorySegmentsFixtures.FixedRecordsSource;

/**
 * The five storage variants for {@code fixedRecords} (id: long, value:
 * long, flag: int, 24-byte stride). Offsets are computed once from a
 * {@link MemoryLayout} and accessed via plain {@code segment.get/set}
 * calls at a precomputed offset — never a layout-path {@code VarHandle}
 * stored on a non-constant field, per content/labs/aos-vs-soa's
 * documented ~100x C2 regression from that pattern.
 */
public final class FixedRecordsStorage {

    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_LONG.withName("id"),
        ValueLayout.JAVA_LONG.withName("value"),
        ValueLayout.JAVA_INT.withName("flag"),
        MemoryLayout.paddingLayout(4));
    private static final long STRIDE = LAYOUT.byteSize(); // 24
    private static final long ID_OFF = LAYOUT.byteOffset(PathElement.groupElement("id"));
    private static final long VALUE_OFF = LAYOUT.byteOffset(PathElement.groupElement("value"));
    private static final long FLAG_OFF = LAYOUT.byteOffset(PathElement.groupElement("flag"));
    private static final long HEADER_BYTES = 4096; // slicedView's "unrelated region before the payload"

    private FixedRecordsStorage() {}

    public static long strideBytes() {
        return STRIDE;
    }

    // ---- heapPrimitiveArray ----

    public static RecordStorage heapPrimitiveArray(FixedRecordsSource s) {
        long[] id = s.id.clone();
        long[] value = s.value.clone();
        int[] flag = s.flag.clone();
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                for (int i = 0; i < id.length; i++) sum += id[i] + value[i] + flag[i];
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) sum += id[idx] + value[idx] + flag[idx];
                return sum;
            }

            @Override
            public void close() {}
        };
    }

    // ---- confinedSegment / sharedSegment (identical mechanics, different Arena kind) ----

    private static MemorySegment writeSegment(Arena arena, FixedRecordsSource s, long baseOffset, long totalBytes) {
        MemorySegment segment = arena.allocate(totalBytes, LAYOUT.byteAlignment());
        int n = s.id.length;
        for (int i = 0; i < n; i++) {
            long base = baseOffset + i * STRIDE;
            segment.set(ValueLayout.JAVA_LONG, base + ID_OFF, s.id[i]);
            segment.set(ValueLayout.JAVA_LONG, base + VALUE_OFF, s.value[i]);
            segment.set(ValueLayout.JAVA_INT, base + FLAG_OFF, s.flag[i]);
        }
        return segment;
    }

    private static RecordStorage segmentStorage(Arena arena, MemorySegment segment, long baseOffset) {
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                long base = baseOffset;
                for (long end = baseOffset + segment.byteSize() - baseOffset; base < end; base += STRIDE) {
                    sum += segment.get(ValueLayout.JAVA_LONG, base + ID_OFF)
                        + segment.get(ValueLayout.JAVA_LONG, base + VALUE_OFF)
                        + segment.get(ValueLayout.JAVA_INT, base + FLAG_OFF);
                }
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) {
                    long base = baseOffset + (long) idx * STRIDE;
                    sum += segment.get(ValueLayout.JAVA_LONG, base + ID_OFF)
                        + segment.get(ValueLayout.JAVA_LONG, base + VALUE_OFF)
                        + segment.get(ValueLayout.JAVA_INT, base + FLAG_OFF);
                }
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }

    public static RecordStorage confinedSegment(FixedRecordsSource s) {
        Arena arena = Arena.ofConfined();
        MemorySegment segment = writeSegment(arena, s, 0, STRIDE * s.id.length);
        return segmentStorage(arena, segment, 0);
    }

    public static RecordStorage sharedSegment(FixedRecordsSource s) {
        Arena arena = Arena.ofShared();
        MemorySegment segment = writeSegment(arena, s, 0, STRIDE * s.id.length);
        return segmentStorage(arena, segment, 0);
    }

    // ---- slicedView: a nonzero-offset view into a larger backing segment ----

    public static RecordStorage slicedView(FixedRecordsSource s) {
        Arena arena = Arena.ofShared();
        long payloadBytes = STRIDE * s.id.length;
        MemorySegment backing = writeSegment(arena, s, HEADER_BYTES, HEADER_BYTES + payloadBytes);
        MemorySegment slice = backing.asSlice(HEADER_BYTES, payloadBytes);
        // slice is itself a zero-based segment (its offset 0 == backing's HEADER_BYTES); use it directly.
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long sum = 0;
                for (long base = 0; base < payloadBytes; base += STRIDE) {
                    sum += slice.get(ValueLayout.JAVA_LONG, base + ID_OFF)
                        + slice.get(ValueLayout.JAVA_LONG, base + VALUE_OFF)
                        + slice.get(ValueLayout.JAVA_INT, base + FLAG_OFF);
                }
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                long sum = 0;
                for (int idx : indices) {
                    long base = (long) idx * STRIDE;
                    sum += slice.get(ValueLayout.JAVA_LONG, base + ID_OFF)
                        + slice.get(ValueLayout.JAVA_LONG, base + VALUE_OFF)
                        + slice.get(ValueLayout.JAVA_INT, base + FLAG_OFF);
                }
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }

    // ---- copiedBoundaryCrossing: data starts off-heap; every operation copies it onto the heap first ----

    public static RecordStorage copiedBoundaryCrossing(FixedRecordsSource s) {
        Arena arena = Arena.ofShared();
        MemorySegment offHeap = writeSegment(arena, s, 0, STRIDE * s.id.length);
        int n = s.id.length;
        return new RecordStorage() {
            @Override
            public long sequentialSum() {
                long[] id = new long[n];
                long[] value = new long[n];
                int[] flag = new int[n];
                for (int i = 0; i < n; i++) {
                    long base = i * STRIDE;
                    id[i] = offHeap.get(ValueLayout.JAVA_LONG, base + ID_OFF);
                    value[i] = offHeap.get(ValueLayout.JAVA_LONG, base + VALUE_OFF);
                    flag[i] = offHeap.get(ValueLayout.JAVA_INT, base + FLAG_OFF);
                }
                long sum = 0;
                for (int i = 0; i < n; i++) sum += id[i] + value[i] + flag[i];
                return sum;
            }

            @Override
            public long randomAccess(int[] indices) {
                // The copy-then-access contract applies uniformly, even for a
                // handful of touched records: the WHOLE segment crosses the
                // boundary first (this variant's defining cost), then the
                // requested indices are summed from the heap copy — never a
                // per-index off-heap read that would understate the trap.
                long[] id = new long[n];
                long[] value = new long[n];
                int[] flag = new int[n];
                for (int i = 0; i < n; i++) {
                    long base = i * STRIDE;
                    id[i] = offHeap.get(ValueLayout.JAVA_LONG, base + ID_OFF);
                    value[i] = offHeap.get(ValueLayout.JAVA_LONG, base + VALUE_OFF);
                    flag[i] = offHeap.get(ValueLayout.JAVA_INT, base + FLAG_OFF);
                }
                long sum = 0;
                for (int idx : indices) sum += id[idx] + value[idx] + flag[idx];
                return sum;
            }

            @Override
            public void close() {
                arena.close();
            }
        };
    }
}
