package pl.kzybala.lab.udpingest;

/**
 * The single wire format every variant in this lab sends/receives: an
 * 8-byte little-endian sequence number followed by a deterministic
 * payload pattern, fixed to exactly one of three total datagram sizes
 * (64B, 256B, 1400B). The sequence number is what lets a receiver detect
 * drops and reordering explicitly, rather than merely counting packets.
 */
public final class DatagramFormat {

    public static final int SEQUENCE_SIZE = 8;

    private DatagramFormat() {
    }

    public static int payloadSize(int datagramSize) {
        return datagramSize - SEQUENCE_SIZE;
    }

    public static byte[] build(long sequence, int datagramSize) {
        byte[] out = new byte[datagramSize];
        writeSequence(out, 0, sequence);
        int payloadSize = payloadSize(datagramSize);
        for (int i = 0; i < payloadSize; i++) {
            out[SEQUENCE_SIZE + i] = (byte) ((sequence + i) & 0xFF);
        }
        return out;
    }

    public static void writeSequence(byte[] buf, int offset, long sequence) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte) (sequence >>> (8 * i));
        }
    }

    public static long readSequence(byte[] buf, int offset) {
        long seq = 0;
        for (int i = 0; i < 8; i++) {
            seq |= (buf[offset + i] & 0xFFL) << (8 * i);
        }
        return seq;
    }

    /** Validates that every payload byte matches the deterministic pattern for {@code sequence}. */
    public static boolean payloadMatches(byte[] buf, int offset, int length, long sequence) {
        int payloadSize = payloadSize(length);
        for (int i = 0; i < payloadSize; i++) {
            byte expected = (byte) ((sequence + i) & 0xFF);
            if (buf[offset + SEQUENCE_SIZE + i] != expected) {
                return false;
            }
        }
        return true;
    }
}
