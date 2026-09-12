package pl.kzybala.lab.binser;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Variant: FFM ({@code java.lang.foreign}) flyweight codec. Encode writes
 * directly into a fixed-size {@link MemorySegment}; decode returns an
 * {@link EventView} — a flyweight that reads fields on demand from that
 * segment rather than materializing an {@link EventRecord} up front. This is
 * the variant that actually removes the "one Java object per decode" cost
 * that {@link ByteBufferCodec} still pays.
 */
public final class FfmFlyweightCodec {

    public byte[] encode(EventRecord event) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(EventRecord.WIRE_SIZE);
            EventView.write(segment, event);
            return segment.toArray(ValueLayout.JAVA_BYTE);
        }
    }

    /**
     * Copies {@code wire} into a fresh confined-arena segment and returns a
     * flyweight view over it. The copy itself is unavoidable here because
     * the caller-owned {@code byte[]} is not addressable memory the segment
     * can alias directly across the JNI-adjacent FFM boundary without
     * pinning; what the view removes is the per-field materialization cost
     * on top of that one copy, not the copy itself.
     */
    public EventView decode(byte[] wire) {
        Arena arena = Arena.ofAuto();
        MemorySegment segment = arena.allocate(EventRecord.WIRE_SIZE);
        MemorySegment.copy(wire, 0, segment, ValueLayout.JAVA_BYTE, 0, wire.length);
        return new EventView(segment);
    }
}
