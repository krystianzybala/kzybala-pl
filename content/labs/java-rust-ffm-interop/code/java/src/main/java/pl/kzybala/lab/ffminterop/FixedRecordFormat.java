package pl.kzybala.lab.ffminterop;

/**
 * The fixed 16-byte record format for the "fixed record validation"
 * dataset: an 8-byte little-endian {@code id} followed by an 8-byte
 * little-endian {@code value}, expected to equal
 * {@code expectedValue(id)} — identical derivation to {@code expected_value}
 * on the Rust side.
 */
public final class FixedRecordFormat {

    public static final int RECORD_SIZE = 16;

    private FixedRecordFormat() {
    }

    public static long expectedValue(long id) {
        return id * 2654435761L + 1;
    }

    public static byte[] buildRecords(int count, java.util.function.LongUnaryOperator valueForId) {
        byte[] out = new byte[count * RECORD_SIZE];
        for (int i = 0; i < count; i++) {
            long id = i;
            long value = valueForId.applyAsLong(id);
            writeLong(out, i * RECORD_SIZE, id);
            writeLong(out, i * RECORD_SIZE + 8, value);
        }
        return out;
    }

    private static void writeLong(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (value >>> (8 * i));
        }
    }
}
