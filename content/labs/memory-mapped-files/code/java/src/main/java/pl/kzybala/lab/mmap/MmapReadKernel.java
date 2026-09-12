package pl.kzybala.lab.mmap;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Variants: mmap sequential and mmap random access. Both map the whole
 * record file read-only and read record fields directly out of the mapped
 * region — no per-record heap allocation, no explicit {@code read()}
 * syscall per record (the OS satisfies each page fault once per page,
 * transparently).
 *
 * <p>"Warm" vs "cold" is not something this kernel can force by itself —
 * page-cache residency is host/OS state, not something a mapping API
 * controls portably without root (see theory.md's Assumptions and scope
 * and the "dropping caches without root/disclosure" trap). This kernel
 * exposes {@link #mapFreshly} (a brand-new mapping over a file this
 * process has not touched) and reuse of an already-mapped, already-read
 * {@link java.nio.MappedByteBuffer} as the two honestly-nameable states
 * this dev machine can produce; genuine OS-page-cache cold/warm
 * disambiguation is native-Linux evidence, captured via
 * {@code posix_fadvise(POSIX_FADV_DONTNEED)} where the runner supports it.
 */
public final class MmapReadKernel {

    public MappedByteBuffer mapFreshly(Path path, long fileSize) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            return mapped;
        }
    }

    public long readSequentialChecksum(MappedByteBuffer mapped, long recordCount) {
        long checksum = 0;
        byte[] payload = new byte[RecordFile.payloadSize()];
        MappedByteBuffer buf = mapped.duplicate();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.clear();
        for (long i = 0; i < recordCount; i++) {
            long id = buf.getLong();
            long value = buf.getLong();
            buf.get(payload);
            checksum += RecordFile.checksumRecord(id, value, payload);
        }
        return checksum;
    }

    /** Reads records in the order given by {@code order} (a permutation of [0, recordCount)). */
    public long readRandomChecksum(MappedByteBuffer mapped, int[] order) {
        long checksum = 0;
        byte[] payload = new byte[RecordFile.payloadSize()];
        for (int idx : order) {
            int offset = idx * RecordFile.RECORD_SIZE;
            long id = mapped.getLong(offset);
            long value = mapped.getLong(offset + 8);
            for (int i = 0; i < payload.length; i++) {
                payload[i] = mapped.get(offset + 16 + i);
            }
            checksum += RecordFile.checksumRecord(id, value, payload);
        }
        return checksum;
    }

    /** Deterministic pseudo-random permutation of [0, count) — same order every run, both languages. */
    public static int[] deterministicPermutation(int count, long seed) {
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        long state = seed;
        for (int i = count - 1; i > 0; i--) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int j = (int) (Long.remainderUnsigned(state >>> 33, i + 1));
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        return order;
    }
}
