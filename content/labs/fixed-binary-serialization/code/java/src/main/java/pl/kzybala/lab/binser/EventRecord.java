package pl.kzybala.lab.binser;

import java.util.Arrays;
import java.util.Objects;

/**
 * The single message shape shared by all four dataset profiles in this lab
 * (small command, medium event, repeated fields, versioned optional field):
 * each profile is simply a different population of the same fixed fields, so
 * every codec variant compiles against one wire contract instead of four.
 *
 * <p>{@code samples} is capped at {@link #MAX_SAMPLES} slots so the record
 * has one fixed on-wire size regardless of how many samples are actually
 * used — a genuinely variable-length array would defeat the point of a
 * fixed-layout flyweight, which needs to know every field's offset without
 * reading the data first.
 */
public record EventRecord(
        byte version,
        long id,
        int deviceId,
        byte opcode,
        byte flags,
        double value,
        long timestamp,
        int sampleCount,
        int[] samples,
        boolean optionalPresent,
        int optionalField) {

    public static final int MAX_SAMPLES = 8;

    /** Exact wire size in bytes for the fixed layout (ByteBuffer/FFM variants). */
    public static final int WIRE_SIZE =
            1 // version
            + 8 // id
            + 4 // deviceId
            + 1 // opcode
            + 1 // flags
            + 8 // value
            + 8 // timestamp
            + 2 // sampleCount
            + 4 * MAX_SAMPLES // samples (fixed-size slot, zero-padded beyond sampleCount)
            + 1 // optionalPresent
            + 4; // optionalField

    public EventRecord {
        if (sampleCount < 0 || sampleCount > MAX_SAMPLES) {
            throw new IllegalArgumentException("sampleCount out of range: " + sampleCount);
        }
        if (samples.length != MAX_SAMPLES) {
            throw new IllegalArgumentException("samples must be exactly " + MAX_SAMPLES + " slots");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventRecord other)) return false;
        return version == other.version
                && id == other.id
                && deviceId == other.deviceId
                && opcode == other.opcode
                && flags == other.flags
                && Double.compare(value, other.value) == 0
                && timestamp == other.timestamp
                && sampleCount == other.sampleCount
                && Arrays.equals(samples, other.samples)
                && optionalPresent == other.optionalPresent
                && optionalField == other.optionalField;
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, id, deviceId, opcode, flags, value, timestamp,
                sampleCount, Arrays.hashCode(samples), optionalPresent, optionalField);
    }
}
