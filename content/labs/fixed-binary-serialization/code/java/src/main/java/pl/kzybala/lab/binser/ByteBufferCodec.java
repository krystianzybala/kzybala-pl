package pl.kzybala.lab.binser;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Variant: hand-written {@link ByteBuffer} codec against the fixed
 * {@link EventRecord#WIRE_SIZE}-byte layout. Every field is written/read at
 * its own explicit offset order — no reflection, no class descriptor, no
 * variable-length framing. Byte order is pinned explicitly to little-endian
 * so the wire format never depends on the JVM's default order (the
 * "hardcoding endianness silently" trap this lab documents is about doing
 * this implicitly, not about picking an order — any fixed, declared order is
 * fine).
 *
 * <p>Decode still allocates one {@link EventRecord} (and its backing
 * {@code samples} array) per call — this variant is "no reflection, no
 * generic framing," not "zero allocation"; that step is what the FFM
 * flyweight variant removes.
 */
public final class ByteBufferCodec {

    public byte[] encode(EventRecord event) {
        ByteBuffer buf = ByteBuffer.allocate(EventRecord.WIRE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(event.version());
        buf.putLong(event.id());
        buf.putInt(event.deviceId());
        buf.put(event.opcode());
        buf.put(event.flags());
        buf.putDouble(event.value());
        buf.putLong(event.timestamp());
        buf.putShort((short) event.sampleCount());
        int[] samples = event.samples();
        for (int i = 0; i < EventRecord.MAX_SAMPLES; i++) {
            buf.putInt(samples[i]);
        }
        buf.put((byte) (event.optionalPresent() ? 1 : 0));
        buf.putInt(event.optionalField());
        return buf.array();
    }

    public EventRecord decode(byte[] wire) {
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
        byte version = buf.get();
        long id = buf.getLong();
        int deviceId = buf.getInt();
        byte opcode = buf.get();
        byte flags = buf.get();
        double value = buf.getDouble();
        long timestamp = buf.getLong();
        int sampleCount = buf.getShort();
        int[] samples = new int[EventRecord.MAX_SAMPLES];
        for (int i = 0; i < EventRecord.MAX_SAMPLES; i++) {
            samples[i] = buf.getInt();
        }
        boolean optionalPresent = buf.get() != 0;
        int optionalField = buf.getInt();
        return new EventRecord(version, id, deviceId, opcode, flags, value, timestamp,
                sampleCount, samples, optionalPresent, optionalField);
    }
}
