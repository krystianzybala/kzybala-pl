package pl.kzybala.lab.mmap;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Variant: mapped write plus flush. Maps a file read-write, writes the
 * same deterministic record pattern {@link RecordFile} defines directly
 * into the mapped region (no explicit {@code write()} syscall per record —
 * dirty pages accumulate until flushed), then calls
 * {@link MappedByteBuffer#force()} to force those dirty pages to storage
 * and measures that flush separately from the write loop itself.
 */
public final class MmapWriteFlushKernel {

    public MappedByteBuffer mapForWrite(Path path, long fileSize) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE)) {
            channel.truncate(fileSize);
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            return mapped;
        }
    }

    /** Writes every record's bytes into the mapping. Does not flush. */
    public void writeAll(MappedByteBuffer mapped, long recordCount) {
        MappedByteBuffer buf = mapped.duplicate();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        byte[] payload = new byte[RecordFile.payloadSize()];
        for (long id = 0; id < recordCount; id++) {
            long value = RecordFile.expectedValue(id);
            for (int i = 0; i < payload.length; i++) {
                payload[i] = RecordFile.expectedPayloadByte(id, i);
            }
            buf.putLong(id);
            buf.putLong(value);
            buf.put(payload);
        }
    }

    /** Forces every dirty page in the mapping to storage; the cost this variant isolates. */
    public void flush(MappedByteBuffer mapped) {
        mapped.force();
    }
}
