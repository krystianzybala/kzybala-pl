package pl.kzybala.lab.shmipc;

/**
 * Deterministic message generation and checksum oracle shared by every
 * variant and by the Rust suite (see
 * {@code code/fixtures/shared-memory-ipc-fixtures.json}).
 */
public final class MessageFixtures {

    private MessageFixtures() {
    }

    public static byte[] payload(long messageIndex, int size) {
        byte[] out = new byte[size];
        for (int j = 0; j < size; j++) {
            out[j] = (byte) ((messageIndex + j) & 0xFF);
        }
        return out;
    }

    public static long checksum(long messageIndex, byte[] payload, int length) {
        long h = messageIndex * 31;
        for (int i = 0; i < length; i++) {
            h = h * 31 + payload[i];
        }
        return h;
    }
}
