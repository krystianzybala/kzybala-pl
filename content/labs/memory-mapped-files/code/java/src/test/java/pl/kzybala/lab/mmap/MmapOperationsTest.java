package pl.kzybala.lab.mmap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MmapOperationsTest {

    private static final long RECORD_COUNT = 4096;
    private static final long FILE_SIZE = RECORD_COUNT * RecordFile.RECORD_SIZE;

    private long expectedChecksum() {
        long checksum = 0;
        byte[] payload = new byte[RecordFile.payloadSize()];
        for (long id = 0; id < RECORD_COUNT; id++) {
            long value = RecordFile.expectedValue(id);
            for (int i = 0; i < payload.length; i++) {
                payload[i] = RecordFile.expectedPayloadByte(id, i);
            }
            checksum += RecordFile.checksumRecord(id, value, payload);
        }
        return checksum;
    }

    @Test
    void bufferedReadMatchesExpectedChecksum(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("records.bin");
        RecordFile.write(file, RECORD_COUNT);
        long checksum = new BufferedReadKernel().readAllChecksum(file, RECORD_COUNT);
        assertEquals(expectedChecksum(), checksum);
    }

    @Test
    void mmapSequentialReadMatchesExpectedChecksum(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("records.bin");
        RecordFile.write(file, RECORD_COUNT);
        MmapReadKernel kernel = new MmapReadKernel();
        MappedByteBuffer mapped = kernel.mapFreshly(file, FILE_SIZE);
        long checksum = kernel.readSequentialChecksum(mapped, RECORD_COUNT);
        assertEquals(expectedChecksum(), checksum);
    }

    @Test
    void mmapRandomReadMatchesExpectedChecksum(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("records.bin");
        RecordFile.write(file, RECORD_COUNT);
        MmapReadKernel kernel = new MmapReadKernel();
        MappedByteBuffer mapped = kernel.mapFreshly(file, FILE_SIZE);
        int[] order = MmapReadKernel.deterministicPermutation((int) RECORD_COUNT, 424242L);
        long checksum = kernel.readRandomChecksum(mapped, order);
        assertEquals(expectedChecksum(), checksum);
    }

    @Test
    void permutationVisitsEveryIndexExactlyOnce() {
        int[] order = MmapReadKernel.deterministicPermutation(4096, 424242L);
        boolean[] seen = new boolean[4096];
        for (int idx : order) {
            assertEquals(false, seen[idx], "index " + idx + " visited twice");
            seen[idx] = true;
        }
        for (boolean s : seen) {
            assertEquals(true, s);
        }
    }

    @Test
    void permutationIsDeterministicAcrossCalls() {
        int[] a = MmapReadKernel.deterministicPermutation(4096, 424242L);
        int[] b = MmapReadKernel.deterministicPermutation(4096, 424242L);
        assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b));
    }

    @Test
    void mmapWriteAndFlushProducesReadableRecords(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("written.bin");
        MmapWriteFlushKernel writeKernel = new MmapWriteFlushKernel();
        MappedByteBuffer mapped = writeKernel.mapForWrite(file, FILE_SIZE);
        writeKernel.writeAll(mapped, RECORD_COUNT);
        writeKernel.flush(mapped);

        MmapReadKernel readKernel = new MmapReadKernel();
        MappedByteBuffer readBack = readKernel.mapFreshly(file, FILE_SIZE);
        long checksum = readKernel.readSequentialChecksum(readBack, RECORD_COUNT);
        assertEquals(expectedChecksum(), checksum);
    }
}
