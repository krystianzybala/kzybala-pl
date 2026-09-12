package pl.kzybala.lab.structlayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static pl.kzybala.lab.structlayout.StructLayoutFixtures.MixedSource;

/**
 * The five layout variants for {@code mixedPrimitiveRecord} (active: 1B,
 * timestamp: 8B, category: 1B, quantity: 4B, flags: 2B, amountTicks: 8B
 * — 24 logical bytes). Unlike a C compiler or Rust's {@code repr(C)},
 * Java's FFM {@code MemoryLayout.structLayout} inserts NO automatic
 * padding between members — every gap below is computed explicitly,
 * following the same natural-alignment algorithm a C/Rust compiler
 * applies automatically, so the Java and Rust byte layouts for the
 * "natural"/"poorFieldOrder"/"optimizedFieldOrder" variants are
 * byte-for-byte identical (rust.md). {@code packedUnaligned} places two
 * fields at offsets their own type would normally forbid, which means
 * reading them requires the FFM API's UNALIGNED {@link ValueLayout}
 * variants (`JAVA_LONG_UNALIGNED`, `JAVA_INT_UNALIGNED`) — the default,
 * ALIGNED layouts throw on a misaligned offset rather than silently
 * reading past a boundary; this repository discovered that constraint
 * while building this layout (java.md).
 */
public final class MixedRecordLayout implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int n;
    private final long stride;
    private final long activeOff;
    private final long timestampOff;
    private final long categoryOff;
    private final long quantityOff;
    private final long flagsOff;
    private final long amountOff;
    private final boolean unaligned;

    private MixedRecordLayout(Arena arena, MemorySegment segment, int n, long stride, long activeOff,
            long timestampOff, long categoryOff, long quantityOff, long flagsOff, long amountOff, boolean unaligned) {
        this.arena = arena;
        this.segment = segment;
        this.n = n;
        this.stride = stride;
        this.activeOff = activeOff;
        this.timestampOff = timestampOff;
        this.categoryOff = categoryOff;
        this.quantityOff = quantityOff;
        this.flagsOff = flagsOff;
        this.amountOff = amountOff;
        this.unaligned = unaligned;
    }

    public long strideBytes() {
        return stride;
    }

    private static MixedRecordLayout build(MixedSource s, long stride, long activeOff, long timestampOff,
            long categoryOff, long quantityOff, long flagsOff, long amountOff, boolean unaligned) {
        return build(s, stride, activeOff, timestampOff, categoryOff, quantityOff, flagsOff, amountOff, unaligned,
            unaligned ? 1 : 8);
    }

    private static MixedRecordLayout build(MixedSource s, long stride, long activeOff, long timestampOff,
            long categoryOff, long quantityOff, long flagsOff, long amountOff, boolean unaligned, long segmentAlignment) {
        int n = s.active.length;
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(stride * n, segmentAlignment);
        ValueLayout.OfLong longLayout = unaligned ? ValueLayout.JAVA_LONG_UNALIGNED : ValueLayout.JAVA_LONG;
        ValueLayout.OfInt intLayout = unaligned ? ValueLayout.JAVA_INT_UNALIGNED : ValueLayout.JAVA_INT;
        ValueLayout.OfShort shortLayout = unaligned ? ValueLayout.JAVA_SHORT_UNALIGNED : ValueLayout.JAVA_SHORT;
        for (int i = 0; i < n; i++) {
            long base = i * stride;
            segment.set(ValueLayout.JAVA_BYTE, base + activeOff, (byte) s.active[i]);
            segment.set(longLayout, base + timestampOff, s.timestamp[i]);
            segment.set(ValueLayout.JAVA_BYTE, base + categoryOff, (byte) s.category[i]);
            segment.set(intLayout, base + quantityOff, s.quantity[i]);
            segment.set(shortLayout, base + flagsOff, (short) s.flags[i]);
            segment.set(longLayout, base + amountOff, s.amountTicks[i]);
        }
        return new MixedRecordLayout(
            arena, segment, n, stride, activeOff, timestampOff, categoryOff, quantityOff, flagsOff, amountOff, unaligned);
    }

    /** timestamp@0(8), active@8(1), category@9(1), [pad6], amountTicks@16(8), quantity@24(4), flags@28(2), [pad2] = 32B. */
    public static MixedRecordLayout natural(MixedSource s) {
        return build(s, 32, 8, 0, 9, 24, 28, 16, false);
    }

    /** active@0(1),[pad7], timestamp@8(8), category@16(1),[pad7], amountTicks@24(8), flags@32(2),[pad2], quantity@36(4) = 40B. */
    public static MixedRecordLayout poorFieldOrder(MixedSource s) {
        return build(s, 40, 0, 8, 16, 36, 32, 24, false);
    }

    /** amountTicks@0(8), timestamp@8(8), quantity@16(4), flags@20(2), active@22(1), category@23(1) = 24B, zero padding. */
    public static MixedRecordLayout optimizedFieldOrder(MixedSource s) {
        return build(s, 24, 22, 8, 23, 16, 20, 0, false);
    }

    /** The optimized 24-byte layout, padded so every record occupies exactly one 64-byte cache line, segment itself 64-byte aligned so record boundaries land on real cache-line boundaries. */
    public static MixedRecordLayout cacheLineAligned(MixedSource s) {
        return build(s, 64, 22, 8, 23, 16, 20, 0, false, 64);
    }

    /** timestamp@0(8, aligned by luck), active@8(1), category@9(1), amountTicks@10(8, UNALIGNED), quantity@18(4, UNALIGNED), flags@22(2) = 24B, zero padding, two unaligned fields. */
    public static MixedRecordLayout packedUnaligned(MixedSource s) {
        return build(s, 24, 8, 0, 9, 18, 22, 10, true);
    }

    public long sum() {
        ValueLayout.OfLong longLayout = unaligned ? ValueLayout.JAVA_LONG_UNALIGNED : ValueLayout.JAVA_LONG;
        ValueLayout.OfInt intLayout = unaligned ? ValueLayout.JAVA_INT_UNALIGNED : ValueLayout.JAVA_INT;
        ValueLayout.OfShort shortLayout = unaligned ? ValueLayout.JAVA_SHORT_UNALIGNED : ValueLayout.JAVA_SHORT;
        long sum = 0;
        long base = 0;
        for (int i = 0; i < n; i++, base += stride) {
            sum += segment.get(ValueLayout.JAVA_BYTE, base + activeOff)
                + segment.get(longLayout, base + timestampOff)
                + segment.get(ValueLayout.JAVA_BYTE, base + categoryOff)
                + segment.get(intLayout, base + quantityOff)
                + segment.get(shortLayout, base + flagsOff)
                + segment.get(longLayout, base + amountOff);
        }
        return sum;
    }

    @Override
    public void close() {
        arena.close();
    }
}
