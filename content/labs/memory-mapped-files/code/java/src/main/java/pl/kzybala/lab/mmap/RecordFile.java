package pl.kzybala.lab.mmap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Shared fixed-record file format for every variant in this lab: each
 * record is exactly {@link #RECORD_SIZE} bytes — an 8-byte id, an 8-byte
 * value, and a 48-byte payload derived deterministically from the id — so
 * every variant (buffered, mmap sequential, mmap random, mmap write) reads
 * or writes the identical byte layout and can be checked against the same
 * checksum oracle.
 */
public final class RecordFile {

    public static final int RECORD_SIZE = 64;
    private static final int PAYLOAD_SIZE = RECORD_SIZE - 16;

    private RecordFile() {
    }

    /** Writes {@code recordCount} deterministic records to {@code path}, creating/truncating it. */
    public static void write(Path path, long recordCount) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            channel.truncate(0);
            ByteBuffer buf = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            for (long id = 0; id < recordCount; id++) {
                buf.clear();
                writeRecord(buf, id);
                buf.flip();
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
            }
        }
    }

    private static void writeRecord(ByteBuffer buf, long id) {
        long value = id * 2654435761L + 1; // deterministic, non-trivial derived value
        buf.putLong(id);
        buf.putLong(value);
        for (int i = 0; i < PAYLOAD_SIZE; i++) {
            buf.put((byte) ((id + i) & 0xFF));
        }
    }

    /** Recomputes the same value/payload an honest reader must observe for record {@code id}. */
    public static long expectedValue(long id) {
        return id * 2654435761L + 1;
    }

    public static byte expectedPayloadByte(long id, int index) {
        return (byte) ((id + index) & 0xFF);
    }

    public static int payloadSize() {
        return PAYLOAD_SIZE;
    }

    /**
     * A single stable checksum over every record's id/value/payload, used by
     * every variant's correctness test and by the benchmark's blackhole sink
     * (so the JIT cannot dead-code-eliminate the read).
     */
    public static long checksumRecord(long id, long value, byte[] payload) {
        long h = id * 31 + value;
        for (byte b : payload) {
            h = h * 31 + b;
        }
        return h;
    }
}
