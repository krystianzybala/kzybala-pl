package pl.kzybala.lab.zerocopy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static pl.kzybala.lab.zerocopy.ZeroCopyFixtures.WireBuffer;

/**
 * The five decoder variants for {@code fixedHeaderPlusVariablePayload}.
 * Every variant reads the identical big-endian wire bytes and reproduces
 * the identical checksum; only how much is copied/allocated, and when
 * validation happens, differs.
 */
public final class FixedHeaderDecoders {

    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int MUTATION_MASK = -1; // 0xFFFFFFFF as a 32-bit XOR mask

    private FixedHeaderDecoders() {}

    private static void validateFrame(MemorySegment segment, int base) {
        if (base < 0 || base + 9 > segment.byteSize()) {
            throw new IllegalStateException("frame header out of bounds at offset " + base);
        }
        int words = segment.get(BE_INT, base + 5);
        long payloadEnd = base + 9L + words * 8L;
        if (words < 0 || payloadEnd > segment.byteSize()) {
            throw new IllegalStateException("frame payload out of bounds at offset " + base);
        }
    }

    /** Copies the payload into a fresh long[] before summing — a genuine, independent copy. */
    public static long copyingDecoder(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int words = wb.segment.get(BE_INT, base + 5);
            long[] payload = new long[words];
            for (int w = 0; w < words; w++) {
                payload[w] = wb.segment.get(BE_LONG, base + 9 + w * 8L);
            }
            sum += msgType + seq;
            for (long v : payload) sum += v;
        }
        return sum;
    }

    /** Builds a boxed List<Long> per message — one allocation per value, not just per message. */
    public static long objectBuildingDecoder(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int words = wb.segment.get(BE_INT, base + 5);
            List<Long> payload = new ArrayList<>(words);
            for (int w = 0; w < words; w++) {
                payload.add(wb.segment.get(BE_LONG, base + 9 + w * 8L));
            }
            sum += msgType + seq;
            for (Long v : payload) sum += v;
        }
        return sum;
    }

    /** Validates bounds once per message, then reads directly from the segment — no copy, no per-value object. */
    public static long validatedZeroCopyView(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            sum += sumViewed(wb.segment, base);
        }
        return sum;
    }

    private static long sumViewed(MemorySegment segment, int base) {
        int msgType = segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
        int seq = segment.get(BE_INT, base + 1);
        int words = segment.get(BE_INT, base + 5);
        long sum = msgType + seq;
        for (int w = 0; w < words; w++) {
            sum += segment.get(BE_LONG, base + 9 + w * 8L);
        }
        return sum;
    }

    /** Defers validation to first field access — for this dataset, validation happens the first time ANY field is read. */
    public static long lazyFieldDecode(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            LazyFrame frame = new LazyFrame(wb.segment, base);
            sum += frame.sum();
        }
        return sum;
    }

    private static final class LazyFrame {
        private final MemorySegment segment;
        private final int base;
        private boolean validated;

        LazyFrame(MemorySegment segment, int base) {
            this.segment = segment;
            this.base = base;
        }

        private void ensureValidated() {
            if (!validated) {
                validateFrame(segment, base);
                validated = true;
            }
        }

        long sum() {
            ensureValidated();
            return sumViewed(segment, base);
        }
    }

    /** Round-trips one field through an XOR mutation (write, verify, restore), then sums like validatedZeroCopyView. */
    public static long mutableInPlaceUpdate(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            long seqOffset = base + 1L;
            int original = wb.segment.get(BE_INT, seqOffset);
            int mutated = original ^ MUTATION_MASK;
            wb.segment.set(BE_INT, seqOffset, mutated);
            int readBack = wb.segment.get(BE_INT, seqOffset);
            if (readBack != mutated) {
                throw new IllegalStateException("in-place mutation round-trip failed at offset " + seqOffset);
            }
            wb.segment.set(BE_INT, seqOffset, original); // restore — buffer is unchanged after this method returns
            sum += sumViewed(wb.segment, base);
        }
        return sum;
    }
}
