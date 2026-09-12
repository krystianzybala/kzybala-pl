package pl.kzybala.lab.structlayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static pl.kzybala.lab.structlayout.StructLayoutFixtures.HeaderSource;
import static pl.kzybala.lab.structlayout.StructLayoutFixtures.PAYLOAD_WORDS;

/**
 * The five layout variants for {@code headerPlusPayload} (msgType: 1B,
 * msgFlags: 1B, sequence: 4B, payload: 8 longs = 64B — 70 logical
 * bytes). A genuinely honest nuance this dataset surfaces: {@code
 * natural} and {@code optimizedFieldOrder} both land at exactly 72
 * bytes — reordering the three small header fields cannot shrink the
 * total below the alignment floor the 64-byte payload's own 8-byte
 * alignment requirement imposes on 6 bytes of odd-sized header fields;
 * reordering only moves WHERE the unavoidable 2 bytes of padding sit.
 * This lab's "assuming field declaration order is universal" trap cuts
 * both ways — do not assume a DIFFERENT order is always better, either,
 * without measuring (java.md).
 */
public final class HeaderPayloadLayout implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment segment;
    private final int n;
    private final long stride;
    private final long msgTypeOff;
    private final long msgFlagsOff;
    private final long sequenceOff;
    private final long payloadOff;
    private final boolean unaligned;

    private HeaderPayloadLayout(Arena arena, MemorySegment segment, int n, long stride, long msgTypeOff,
            long msgFlagsOff, long sequenceOff, long payloadOff, boolean unaligned) {
        this.arena = arena;
        this.segment = segment;
        this.n = n;
        this.stride = stride;
        this.msgTypeOff = msgTypeOff;
        this.msgFlagsOff = msgFlagsOff;
        this.sequenceOff = sequenceOff;
        this.payloadOff = payloadOff;
        this.unaligned = unaligned;
    }

    public long strideBytes() {
        return stride;
    }

    private static HeaderPayloadLayout build(HeaderSource s, long stride, long msgTypeOff, long msgFlagsOff,
            long sequenceOff, long payloadOff, boolean unaligned, long segmentAlignment) {
        int n = s.msgType.length;
        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(stride * n, segmentAlignment);
        ValueLayout.OfInt intLayout = unaligned ? ValueLayout.JAVA_INT_UNALIGNED : ValueLayout.JAVA_INT;
        ValueLayout.OfLong longLayout = unaligned ? ValueLayout.JAVA_LONG_UNALIGNED : ValueLayout.JAVA_LONG;
        for (int i = 0; i < n; i++) {
            long base = i * stride;
            segment.set(ValueLayout.JAVA_BYTE, base + msgTypeOff, (byte) s.msgType[i]);
            segment.set(ValueLayout.JAVA_BYTE, base + msgFlagsOff, (byte) s.msgFlags[i]);
            segment.set(intLayout, base + sequenceOff, s.sequence[i]);
            for (int w = 0; w < PAYLOAD_WORDS; w++) {
                segment.set(longLayout, base + payloadOff + w * 8L, s.payload[i][w]);
            }
        }
        return new HeaderPayloadLayout(arena, segment, n, stride, msgTypeOff, msgFlagsOff, sequenceOff, payloadOff, unaligned);
    }

    /** msgType@0(1), msgFlags@1(1), [pad2], sequence@4(4), payload@8(64) = 72B. */
    public static HeaderPayloadLayout natural(HeaderSource s) {
        return build(s, 72, 0, 1, 4, 8, false, 8);
    }

    /** msgFlags@0(1), [pad3], sequence@4(4), msgType@8(1), [pad7], payload@16(64) = 80B. */
    public static HeaderPayloadLayout poorFieldOrder(HeaderSource s) {
        return build(s, 80, 8, 0, 4, 16, false, 8);
    }

    /** sequence@0(4), msgType@4(1), msgFlags@5(1), [pad2], payload@8(64) = 72B — the SAME total as natural (see class doc). */
    public static HeaderPayloadLayout optimizedFieldOrder(HeaderSource s) {
        return build(s, 72, 4, 5, 0, 8, false, 8);
    }

    /** natural's header, payload block pushed to offset 64 so it occupies its own dedicated cache line, never split across two = 128B. */
    public static HeaderPayloadLayout cacheLineAligned(HeaderSource s) {
        return build(s, 128, 0, 1, 4, 64, false, 64);
    }

    /** msgType@0(1), msgFlags@1(1), sequence@2(4, UNALIGNED), payload@6(64, UNALIGNED) = 70B, zero padding, two unaligned regions. */
    public static HeaderPayloadLayout packedUnaligned(HeaderSource s) {
        return build(s, 70, 0, 1, 2, 6, true, 1);
    }

    public long sum() {
        ValueLayout.OfInt intLayout = unaligned ? ValueLayout.JAVA_INT_UNALIGNED : ValueLayout.JAVA_INT;
        ValueLayout.OfLong longLayout = unaligned ? ValueLayout.JAVA_LONG_UNALIGNED : ValueLayout.JAVA_LONG;
        long sum = 0;
        long base = 0;
        for (int i = 0; i < n; i++, base += stride) {
            // msgFlags' fixture range is 0..255 — a signed Java byte can only
            // hold -128..127, so the stored byte must be reinterpreted as
            // unsigned (& 0xFF) on read, or values 128..255 come back negative.
            sum += segment.get(ValueLayout.JAVA_BYTE, base + msgTypeOff)
                + (segment.get(ValueLayout.JAVA_BYTE, base + msgFlagsOff) & 0xFF)
                + segment.get(intLayout, base + sequenceOff);
            for (int w = 0; w < PAYLOAD_WORDS; w++) {
                sum += segment.get(longLayout, base + payloadOff + w * 8L);
            }
        }
        return sum;
    }

    @Override
    public void close() {
        arena.close();
    }
}
