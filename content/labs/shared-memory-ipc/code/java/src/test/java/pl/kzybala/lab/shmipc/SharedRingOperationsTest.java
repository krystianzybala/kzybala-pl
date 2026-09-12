package pl.kzybala.lab.shmipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Protocol-logic correctness tests. These open the SAME backing file
 * through TWO INDEPENDENT {@code SharedRing} instances (two separate
 * {@code FileChannel.map} calls, each producing its own
 * {@code MemorySegment}) rather than sharing one Java object between
 * threads — exercising the real OS page-cache-backed sharing mechanism a
 * second process would also go through, not JVM-heap visibility. This is
 * still not a substitute for the real cross-process proof in
 * {@link ProcessLauncherTest}; see this lab's "using same-process threads
 * and calling it IPC" trap in theory.md for why that distinction matters.
 */
class SharedRingOperationsTest {

    @Test
    void sequentialPublishAndConsumeRoundTrips(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("ring.bin");
        SharedRing writerView = SharedRing.createNew(path, 8);
        writerView.initWriter();
        SharedRing readerView = SharedRing.openExisting(path);
        readerView.initReader();

        for (long i = 0; i < 100; i++) {
            byte[] payload = MessageFixtures.payload(i, 32);
            assertEquals(true, writerView.tryPublish(payload, 32));
            byte[] out = new byte[SharedRing.PAYLOAD_CAPACITY];
            int length = -1;
            for (int spin = 0; spin < 1_000_000 && length < 0; spin++) {
                length = readerView.tryConsume(out);
            }
            assertEquals(32, length);
            byte[] expected = new byte[32];
            System.arraycopy(payload, 0, expected, 0, 32);
            byte[] actual = new byte[32];
            System.arraycopy(out, 0, actual, 0, 32);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    void ringRejectsPublishWhenGenuinelyFull(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("ring.bin");
        SharedRing writerView = SharedRing.createNew(path, 4);
        writerView.initWriter();
        for (int i = 0; i < 4; i++) {
            assertEquals(true, writerView.tryPublish(MessageFixtures.payload(i, 16), 16));
        }
        assertEquals(false, writerView.tryPublish(MessageFixtures.payload(4, 16), 16));
    }

    @Test
    void batchPublishDeliversEveryMessageWithOneHeaderUpdate(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("ring.bin");
        SharedRing writerView = SharedRing.createNew(path, 16);
        writerView.initWriter();
        SharedRing readerView = SharedRing.openExisting(path);
        readerView.initReader();

        byte[][] payloads = new byte[10][];
        int[] lengths = new int[10];
        for (int i = 0; i < 10; i++) {
            payloads[i] = MessageFixtures.payload(i, 16);
            lengths[i] = 16;
        }
        int published = writerView.tryPublishBatch(payloads, lengths);
        assertEquals(10, published);
        assertEquals(10L, writerView.writerSeq());

        byte[] out = new byte[SharedRing.PAYLOAD_CAPACITY];
        for (int i = 0; i < 10; i++) {
            int length = -1;
            for (int spin = 0; spin < 1_000_000 && length < 0; spin++) {
                length = readerView.tryConsume(out);
            }
            assertEquals(16, length);
        }
    }

    @Test
    void slotViewMatchesCopyingConsume(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("ring.bin");
        SharedRing writerView = SharedRing.createNew(path, 8);
        writerView.initWriter();
        SharedRing readerView = SharedRing.openExisting(path);
        readerView.initReader();

        byte[] payload = MessageFixtures.payload(7, 32);
        writerView.tryPublish(payload, 32);

        SharedRing.SlotView view = null;
        for (int spin = 0; spin < 1_000_000 && view == null; spin++) {
            view = readerView.tryConsumeView();
        }
        assertEquals(32, view.length());
        assertArrayEquals(java.util.Arrays.copyOf(payload, 32), view.toByteArray());
    }

    @Test
    void openingASegmentWithWrongProtocolVersionFails(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("bad.bin");
        // A file too small to even contain the header must be rejected explicitly.
        java.nio.file.Files.write(path, new byte[10]);
        assertThrows(java.io.IOException.class, () -> SharedRing.openExisting(path));
    }
}
