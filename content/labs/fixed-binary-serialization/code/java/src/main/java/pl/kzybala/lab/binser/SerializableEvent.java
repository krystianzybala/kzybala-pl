package pl.kzybala.lab.binser;

import java.io.Serializable;
import java.util.Arrays;

/**
 * A plain, {@link Serializable} mirror of {@link EventRecord}, used only by
 * {@link GenericObjectCodec}. This is the "generic object codec" baseline:
 * JDK built-in serialization, the only generic serializer already available
 * in this repository without adding a new dependency — its class descriptor
 * and reflection-based field walk are exactly the overhead this lab measures
 * against the fixed-layout variants.
 */
public final class SerializableEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private byte version;
    private long id;
    private int deviceId;
    private byte opcode;
    private byte flags;
    private double value;
    private long timestamp;
    private int sampleCount;
    private int[] samples;
    private boolean optionalPresent;
    private int optionalField;

    public SerializableEvent() {
    }

    public static SerializableEvent fromRecord(EventRecord r) {
        SerializableEvent e = new SerializableEvent();
        e.version = r.version();
        e.id = r.id();
        e.deviceId = r.deviceId();
        e.opcode = r.opcode();
        e.flags = r.flags();
        e.value = r.value();
        e.timestamp = r.timestamp();
        e.sampleCount = r.sampleCount();
        e.samples = Arrays.copyOf(r.samples(), r.samples().length);
        e.optionalPresent = r.optionalPresent();
        e.optionalField = r.optionalField();
        return e;
    }

    public EventRecord toRecord() {
        return new EventRecord(version, id, deviceId, opcode, flags, value, timestamp,
                sampleCount, Arrays.copyOf(samples, samples.length), optionalPresent, optionalField);
    }
}
