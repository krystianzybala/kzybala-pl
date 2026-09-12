package pl.kzybala.lab.binser;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A flyweight view over a fixed {@link EventRecord#WIRE_SIZE}-byte
 * {@link MemorySegment}: every accessor reads directly from the backing
 * memory at its fixed offset, on demand. No Java object graph for the
 * message is built until (and unless) {@link #toRecord()} is called — a
 * caller that only needs {@code deviceId()} out of a batch of messages
 * never pays to materialize the rest.
 *
 * <p>Offsets mirror {@link ByteBufferCodec} exactly (same field order, same
 * little-endian byte order) so the two fixed-layout variants are wire
 * compatible and the equivalence fixture can be shared between them.
 */
public final class EventView {

    private static final int OFF_VERSION = 0;
    private static final int OFF_ID = 1;
    private static final int OFF_DEVICE_ID = 9;
    private static final int OFF_OPCODE = 13;
    private static final int OFF_FLAGS = 14;
    private static final int OFF_VALUE = 15;
    private static final int OFF_TIMESTAMP = 23;
    private static final int OFF_SAMPLE_COUNT = 31;
    private static final int OFF_SAMPLES = 33;
    private static final int OFF_OPTIONAL_PRESENT = OFF_SAMPLES + 4 * EventRecord.MAX_SAMPLES;
    private static final int OFF_OPTIONAL_FIELD = OFF_OPTIONAL_PRESENT + 1;

    private final MemorySegment segment;

    public EventView(MemorySegment segment) {
        if (segment.byteSize() != EventRecord.WIRE_SIZE) {
            throw new IllegalArgumentException("segment must be exactly " + EventRecord.WIRE_SIZE + " bytes");
        }
        this.segment = segment;
    }

    public byte version() {
        return segment.get(ValueLayout.JAVA_BYTE, OFF_VERSION);
    }

    public long id() {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_ID);
    }

    public int deviceId() {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_DEVICE_ID);
    }

    public byte opcode() {
        return segment.get(ValueLayout.JAVA_BYTE, OFF_OPCODE);
    }

    public byte flags() {
        return segment.get(ValueLayout.JAVA_BYTE, OFF_FLAGS);
    }

    public double value() {
        long bits = segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_VALUE);
        return Double.longBitsToDouble(bits);
    }

    public long timestamp() {
        return segment.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_TIMESTAMP);
    }

    public int sampleCount() {
        return Short.toUnsignedInt(segment.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_SAMPLE_COUNT));
    }

    public int sample(int index) {
        if (index < 0 || index >= EventRecord.MAX_SAMPLES) {
            throw new IndexOutOfBoundsException("sample index: " + index);
        }
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_SAMPLES + 4L * index);
    }

    public boolean optionalPresent() {
        return segment.get(ValueLayout.JAVA_BYTE, OFF_OPTIONAL_PRESENT) != 0;
    }

    public int optionalField() {
        return segment.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), OFF_OPTIONAL_FIELD);
    }

    /** Materializes the full record — the cost this flyweight lets a caller avoid paying eagerly. */
    public EventRecord toRecord() {
        int[] samples = new int[EventRecord.MAX_SAMPLES];
        for (int i = 0; i < EventRecord.MAX_SAMPLES; i++) {
            samples[i] = sample(i);
        }
        return new EventRecord(version(), id(), deviceId(), opcode(), flags(), value(), timestamp(),
                sampleCount(), samples, optionalPresent(), optionalField());
    }

    public static void write(MemorySegment segment, EventRecord event) {
        if (segment.byteSize() != EventRecord.WIRE_SIZE) {
            throw new IllegalArgumentException("segment must be exactly " + EventRecord.WIRE_SIZE + " bytes");
        }
        var le = java.nio.ByteOrder.LITTLE_ENDIAN;
        segment.set(ValueLayout.JAVA_BYTE, OFF_VERSION, event.version());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(le), OFF_ID, event.id());
        segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(le), OFF_DEVICE_ID, event.deviceId());
        segment.set(ValueLayout.JAVA_BYTE, OFF_OPCODE, event.opcode());
        segment.set(ValueLayout.JAVA_BYTE, OFF_FLAGS, event.flags());
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(le), OFF_VALUE, Double.doubleToLongBits(event.value()));
        segment.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(le), OFF_TIMESTAMP, event.timestamp());
        segment.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(le), OFF_SAMPLE_COUNT, (short) event.sampleCount());
        int[] samples = event.samples();
        for (int i = 0; i < EventRecord.MAX_SAMPLES; i++) {
            segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(le), OFF_SAMPLES + 4L * i, samples[i]);
        }
        segment.set(ValueLayout.JAVA_BYTE, OFF_OPTIONAL_PRESENT, (byte) (event.optionalPresent() ? 1 : 0));
        segment.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(le), OFF_OPTIONAL_FIELD, event.optionalField());
    }
}
