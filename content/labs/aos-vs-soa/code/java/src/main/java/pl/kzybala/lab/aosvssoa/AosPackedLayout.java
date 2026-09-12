package pl.kzybala.lab.aosvssoa;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Variant 2: AoS packed / off-heap. One contiguous {@link MemorySegment}
 * holds every record back-to-back as {@code (hotA, hotB, cold[0..coldWords))}
 * with no object header, no reference indirection and no GC involvement —
 * the layout this lab's Rust {@code #[repr(C)]} struct mirrors exactly
 * (same field order, same stride). The per-record byte layout is computed
 * once from a runtime-sized {@link MemoryLayout} (the cold sequence
 * length is a dataset parameter — the layout is data, not a compiled
 * type), then accessed through plain {@code segment.get/set(ValueLayout,
 * long offset)} calls rather than layout-path {@code VarHandle}s: the
 * two are equally valid FFM idioms, but a {@code VarHandle} built from a
 * multi-level sequence path and stored in a non-constant field is not
 * reliably intrinsified by C2 — this lab measured a real ~100x
 * regression from that pattern during development (java.md) and uses the
 * offset form specifically because this lab's numbers must reflect the
 * layout, not an accidental JIT pessimization.
 */
public final class AosPackedLayout implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int n;
    private final int coldWords;
    private final long recordStrideBytes;
    private final long hotAOffset;
    private final long hotBOffset;
    private final long coldBaseOffset;

    private AosPackedLayout(Arena arena, MemorySegment segment, int n, int coldWords, long recordStrideBytes,
            long hotAOffset, long hotBOffset, long coldBaseOffset) {
        this.arena = arena;
        this.segment = segment;
        this.n = n;
        this.coldWords = coldWords;
        this.recordStrideBytes = recordStrideBytes;
        this.hotAOffset = hotAOffset;
        this.hotBOffset = hotBOffset;
        this.coldBaseOffset = coldBaseOffset;
    }

    public static AosPackedLayout of(AosVsSoaFixtures.Generated data, int coldWords) {
        int n = data.hotA.length;
        MemoryLayout recordLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("hotA"),
            ValueLayout.JAVA_LONG.withName("hotB"),
            MemoryLayout.sequenceLayout(coldWords, ValueLayout.JAVA_LONG).withName("cold"));
        long recordStrideBytes = recordLayout.byteSize();
        long hotAOffset = recordLayout.byteOffset(PathElement.groupElement("hotA"));
        long hotBOffset = recordLayout.byteOffset(PathElement.groupElement("hotB"));
        long coldBaseOffset = recordLayout.byteOffset(PathElement.groupElement("cold"), PathElement.sequenceElement(0));

        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(recordStrideBytes * n, recordLayout.byteAlignment());
        AosPackedLayout layout = new AosPackedLayout(
            arena, segment, n, coldWords, recordStrideBytes, hotAOffset, hotBOffset, coldBaseOffset);
        for (int i = 0; i < n; i++) {
            long base = i * recordStrideBytes;
            segment.set(ValueLayout.JAVA_LONG, base + hotAOffset, data.hotA[i]);
            segment.set(ValueLayout.JAVA_LONG, base + hotBOffset, data.hotB[i]);
            for (int w = 0; w < coldWords; w++) {
                segment.set(ValueLayout.JAVA_LONG, base + coldBaseOffset + w * 8L, data.cold[i][w]);
            }
        }
        return layout;
    }

    public long recordStrideBytes() {
        return recordStrideBytes;
    }

    public long totalBytes() {
        return recordStrideBytes * n;
    }

    /** One operation = one full pass touching only the hot fields. */
    public long sumHot() {
        long sum = 0;
        long base = 0;
        for (int i = 0; i < n; i++, base += recordStrideBytes) {
            sum += segment.get(ValueLayout.JAVA_LONG, base + hotAOffset) + segment.get(ValueLayout.JAVA_LONG, base + hotBOffset);
        }
        return sum;
    }

    public long coldChecksum() {
        long c = 0;
        long base = 0;
        for (int i = 0; i < n; i++, base += recordStrideBytes) {
            for (int w = 0; w < coldWords; w++) {
                c = c * 31 + segment.get(ValueLayout.JAVA_LONG, base + coldBaseOffset + w * 8L);
            }
        }
        return c;
    }

    @Override
    public void close() {
        arena.close();
    }
}
