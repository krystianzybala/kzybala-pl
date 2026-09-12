package pl.kzybala.lab.syscallamort;

/**
 * The shared wire format: a fixed 16-byte header (8-byte little-endian
 * sequence number, 8-byte little-endian payload length) followed by a
 * variable-length payload with deterministic content — variable length
 * so the "mixed sizes" dataset profile is representable while the header
 * itself stays fixed-size and separately addressable, which is exactly
 * what makes scatter/gather ("write the header and payload without
 * copying them together first") possible.
 */
public final class MessageFormat {

    public static final int HEADER_SIZE = 16;

    private MessageFormat() {
    }

    public static byte[] buildHeader(long sequence, long payloadLength) {
        byte[] header = new byte[HEADER_SIZE];
        writeLong(header, 0, sequence);
        writeLong(header, 8, payloadLength);
        return header;
    }

    public static byte[] buildPayload(long sequence, int length) {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) {
            payload[i] = (byte) ((sequence + i) & 0xFF);
        }
        return payload;
    }

    public static void writeLong(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    public static long readLong(byte[] buf, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (buf[offset + i] & 0xFFL) << (8 * i);
        }
        return v;
    }

    public static boolean payloadMatches(byte[] payload, long sequence) {
        for (int i = 0; i < payload.length; i++) {
            byte expected = (byte) ((sequence + i) & 0xFF);
            if (payload[i] != expected) {
                return false;
            }
        }
        return true;
    }
}
