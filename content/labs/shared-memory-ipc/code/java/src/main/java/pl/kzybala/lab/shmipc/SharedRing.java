package pl.kzybala.lab.shmipc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.IOException;

/**
 * A versioned single-producer/single-consumer ring protocol laid out
 * directly over a memory-mapped, shared-backing-store segment — the
 * cross-process analogue of the
 * <a href="/lab/spsc-ring-buffer/">SPSC Ring Buffer</a> lab's cursor
 * protocol: exactly one process ever writes {@code writerSeq}, exactly one
 * ever writes {@code readerSeq}, and publication/acknowledgement use the
 * same release/acquire discipline that lab establishes. What is new here
 * is that "the other side" is a genuinely different OS process mapping the
 * same backing file, not another thread in the same JVM/process — release
 * and acquire ordering here is what makes cross-process visibility
 * correct, not merely cross-thread visibility.
 *
 * <p>Header layout (cache-line-separated so writer and reader cursors
 * never share a line):
 * <pre>
 * offset 0    : capacity      (int,  4 bytes)
 * offset 4    : protocolVersion (int, 4 bytes)
 * offset 64   : writerSeq     (long, 8 bytes) — own cache line
 * offset 128  : readerSeq     (long, 8 bytes) — own cache line
 * offset 192  : first slot begins here
 * </pre>
 * Slot layout ({@link #SLOT_SIZE} bytes each): a 2-byte length prefix, a
 * 1-byte protocol version, a 1-byte reserved flags byte, then up to
 * {@link #PAYLOAD_CAPACITY} bytes of payload.
 */
public final class SharedRing {

    public static final int PROTOCOL_VERSION = 1;
    public static final int HEADER_SIZE = 192;
    public static final int SLOT_SIZE = 1024;
    public static final int SLOT_HEADER_SIZE = 4;
    public static final int PAYLOAD_CAPACITY = SLOT_SIZE - SLOT_HEADER_SIZE;

    private static final long OFF_CAPACITY = 0;
    private static final long OFF_PROTOCOL_VERSION = 4;
    private static final long OFF_WRITER_SEQ = 64;
    private static final long OFF_READER_SEQ = 128;

    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_SHORT =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    // Cursor fields use the NATURALLY-ALIGNED long layout (not _UNALIGNED):
    // VarHandle access modes beyond plain get/set (setRelease/getAcquire) are
    // only supported on naturally-aligned layouts. The header offsets
    // (64, 128) are always 8-byte aligned because mmap returns page-aligned
    // (far coarser than 8 bytes) base addresses.
    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MemorySegment segment;
    private final int capacity;

    private SharedRing(MemorySegment segment, int capacity) {
        this.segment = segment;
        this.capacity = capacity;
    }

    public static long segmentSize(int capacity) {
        return HEADER_SIZE + (long) capacity * SLOT_SIZE;
    }

    /** Creates (or truncates) the backing file and initializes a fresh ring header. Writer-side entry point. */
    public static SharedRing createNew(Path path, int capacity) throws IOException {
        long size = segmentSize(capacity);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.truncate(size);
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, Arena.ofShared());
            mapped.set(LE_INT, OFF_CAPACITY, capacity);
            mapped.set(LE_INT, OFF_PROTOCOL_VERSION, PROTOCOL_VERSION);
            mapped.set(LE_LONG, OFF_WRITER_SEQ, 0L);
            mapped.set(LE_LONG, OFF_READER_SEQ, 0L);
            return new SharedRing(mapped, capacity);
        }
    }

    /** Opens an existing segment (any process — producer resuming, or a consumer). */
    public static SharedRing openExisting(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long fileSize = channel.size();
            if (fileSize < HEADER_SIZE) {
                throw new IOException("segment file too small to contain a header: " + fileSize);
            }
            MemorySegment probe = channel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_SIZE, Arena.ofShared());
            int protocolVersion = probe.get(LE_INT, OFF_PROTOCOL_VERSION);
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IOException("protocol version mismatch: segment=" + protocolVersion
                        + " reader=" + PROTOCOL_VERSION);
            }
            int capacity = probe.get(LE_INT, OFF_CAPACITY);
            long size = segmentSize(capacity);
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, Arena.ofShared());
            return new SharedRing(mapped, capacity);
        }
    }

    public int capacity() {
        return capacity;
    }

    // --- Writer side ---

    public long writerSeq() {
        return (long) LE_LONG.varHandle().get(segment, OFF_WRITER_SEQ);
    }

    private long cachedTail = 0;
    private long reserveIndex = -1; // -1 = uninitialized; resumed writers must call resumeFrom()

    /** Fresh writer starting a brand-new segment. */
    public void initWriter() {
        this.reserveIndex = 0;
        this.cachedTail = 0;
    }

    /** A writer resuming after a restart — continues from the segment's own recorded cursor. */
    public void resumeWriter() {
        this.reserveIndex = writerSeq();
        this.cachedTail = readerSeqAcquire();
    }

    /** Publishes one message. Returns false if the ring is genuinely full. */
    public boolean tryPublish(byte[] payload, int length) {
        if (reserveIndex < 0) {
            throw new IllegalStateException("call initWriter()/resumeWriter() first");
        }
        if (length > PAYLOAD_CAPACITY) {
            throw new IllegalArgumentException("payload too large: " + length);
        }
        if (reserveIndex - cachedTail == capacity) {
            cachedTail = readerSeqAcquire();
            if (reserveIndex - cachedTail == capacity) {
                return false;
            }
        }
        writeSlot(reserveIndex, payload, length);
        reserveIndex++;
        setWriterSeqRelease(reserveIndex);
        return true;
    }

    /** Publishes a batch of messages with exactly one header (writerSeq) update at the end. */
    public int tryPublishBatch(byte[][] payloads, int[] lengths) {
        int published = 0;
        for (int i = 0; i < payloads.length; i++) {
            if (reserveIndex - cachedTail == capacity) {
                cachedTail = readerSeqAcquire();
                if (reserveIndex - cachedTail == capacity) {
                    break;
                }
            }
            writeSlot(reserveIndex, payloads[i], lengths[i]);
            reserveIndex++;
            published++;
        }
        if (published > 0) {
            setWriterSeqRelease(reserveIndex);
        }
        return published;
    }

    private void writeSlot(long index, byte[] payload, int length) {
        long slotOffset = HEADER_SIZE + (index % capacity) * (long) SLOT_SIZE;
        segment.set(LE_SHORT, slotOffset, (short) length);
        segment.set(ValueLayout.JAVA_BYTE, slotOffset + 2, (byte) PROTOCOL_VERSION);
        segment.set(ValueLayout.JAVA_BYTE, slotOffset + 3, (byte) 0);
        MemorySegment.copy(payload, 0, segment, ValueLayout.JAVA_BYTE, slotOffset + SLOT_HEADER_SIZE, length);
    }

    private void setWriterSeqRelease(long value) {
        LE_LONG.varHandle().setRelease(segment, OFF_WRITER_SEQ, value);
    }

    // --- Reader side ---

    private long cachedHead = 0;
    private long readIndex = -1;

    public void initReader() {
        this.readIndex = readerSeq();
        this.cachedHead = 0;
    }

    public long readerSeq() {
        return (long) LE_LONG.varHandle().get(segment, OFF_READER_SEQ);
    }

    private long readerSeqAcquire() {
        return (long) LE_LONG.varHandle().getAcquire(segment, OFF_READER_SEQ);
    }

    private long writerSeqAcquire() {
        return (long) LE_LONG.varHandle().getAcquire(segment, OFF_WRITER_SEQ);
    }

    /** Reads one message's raw slot bytes into {@code out} (must be >= PAYLOAD_CAPACITY); returns the length, or -1 if empty. */
    public int tryConsume(byte[] out) {
        if (readIndex < 0) {
            throw new IllegalStateException("call initReader() first");
        }
        if (readIndex == cachedHead) {
            cachedHead = writerSeqAcquire();
            if (readIndex == cachedHead) {
                return -1;
            }
        }
        long slotOffset = HEADER_SIZE + (readIndex % capacity) * (long) SLOT_SIZE;
        int length = Short.toUnsignedInt(segment.get(LE_SHORT, slotOffset));
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, slotOffset + SLOT_HEADER_SIZE, out, 0, length);
        readIndex++;
        setReaderSeqRelease(readIndex);
        return length;
    }

    /** Zero-copy variant: returns a view over the slot without copying payload bytes out. */
    public SlotView tryConsumeView() {
        if (readIndex < 0) {
            throw new IllegalStateException("call initReader() first");
        }
        if (readIndex == cachedHead) {
            cachedHead = writerSeqAcquire();
            if (readIndex == cachedHead) {
                return null;
            }
        }
        long slotOffset = HEADER_SIZE + (readIndex % capacity) * (long) SLOT_SIZE;
        int length = Short.toUnsignedInt(segment.get(LE_SHORT, slotOffset));
        SlotView view = new SlotView(segment, slotOffset + SLOT_HEADER_SIZE, length);
        readIndex++;
        setReaderSeqRelease(readIndex);
        return view;
    }

    private void setReaderSeqRelease(long value) {
        LE_LONG.varHandle().setRelease(segment, OFF_READER_SEQ, value);
    }

    /** A zero-copy view over one consumed slot's payload bytes, valid only until the slot is reused. */
    public static final class SlotView {
        private final MemorySegment segment;
        private final long offset;
        private final int length;

        SlotView(MemorySegment segment, long offset, int length) {
            this.segment = segment;
            this.offset = offset;
            this.length = length;
        }

        public int length() {
            return length;
        }

        public byte byteAt(int i) {
            if (i < 0 || i >= length) {
                throw new IndexOutOfBoundsException(String.valueOf(i));
            }
            return segment.get(ValueLayout.JAVA_BYTE, offset + i);
        }

        public byte[] toByteArray() {
            byte[] out = new byte[length];
            MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, out, 0, length);
            return out;
        }
    }
}
