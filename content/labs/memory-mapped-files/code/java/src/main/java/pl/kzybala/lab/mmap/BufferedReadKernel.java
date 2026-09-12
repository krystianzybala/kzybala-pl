package pl.kzybala.lab.mmap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Variant: buffered read. Every record is copied from the OS page cache
 * into a heap {@code byte[]} via {@link FileChannel#read(ByteBuffer)} —
 * the baseline every mmap variant is measured against.
 */
public final class BufferedReadKernel {

    public long readAllChecksum(Path path, long recordCount) throws IOException {
        long checksum = 0;
        ByteBuffer buf = ByteBuffer.allocate(RecordFile.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        byte[] payload = new byte[RecordFile.payloadSize()];
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            for (long i = 0; i < recordCount; i++) {
                buf.clear();
                while (buf.hasRemaining()) {
                    int n = channel.read(buf);
                    if (n < 0) {
                        throw new IOException("unexpected EOF at record " + i);
                    }
                }
                buf.flip();
                long id = buf.getLong();
                long value = buf.getLong();
                buf.get(payload);
                checksum += RecordFile.checksumRecord(id, value, payload);
            }
        }
        return checksum;
    }
}
