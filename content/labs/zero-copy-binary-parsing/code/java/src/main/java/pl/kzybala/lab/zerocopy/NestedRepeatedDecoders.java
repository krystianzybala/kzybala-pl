package pl.kzybala.lab.zerocopy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static pl.kzybala.lab.zerocopy.ZeroCopyFixtures.WireBuffer;

/**
 * The five decoder variants for {@code nestedRepeatedFields} — a
 * message containing a repeated list of small (id, amount) sub-records.
 * Every variant reads the identical big-endian wire bytes and reproduces
 * the identical checksum; only how the repeated records are
 * materialized (or not) differs.
 */
public final class NestedRepeatedDecoders {

    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final int MUTATION_MASK = -1;

    private NestedRepeatedDecoders() {}

    private static void validateFrame(MemorySegment segment, int base) {
        if (base < 0 || base + 9 > segment.byteSize()) {
            throw new IllegalStateException("frame header out of bounds at offset " + base);
        }
        int count = segment.get(BE_INT, base + 5);
        long payloadEnd = base + 9L + count * 12L;
        if (count < 0 || payloadEnd > segment.byteSize()) {
            throw new IllegalStateException("frame payload out of bounds at offset " + base);
        }
    }

    public record SubRecord(int id, long amount) {}

    /** Copies every sub-record's raw bytes into fresh int[]/long[] arrays before summing. */
    public static long copyingDecoder(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int count = wb.segment.get(BE_INT, base + 5);
            int[] ids = new int[count];
            long[] amounts = new long[count];
            long recBase = base + 9L;
            for (int r = 0; r < count; r++) {
                ids[r] = wb.segment.get(BE_INT, recBase);
                amounts[r] = wb.segment.get(BE_LONG, recBase + 4);
                recBase += 12;
            }
            sum += msgType + seq;
            for (int r = 0; r < count; r++) sum += ids[r] + amounts[r];
        }
        return sum;
    }

    /** Builds one SubRecord object per repeated entry — real object-graph allocation. */
    public static long objectBuildingDecoder(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int count = wb.segment.get(BE_INT, base + 5);
            List<SubRecord> records = new ArrayList<>(count);
            long recBase = base + 9L;
            for (int r = 0; r < count; r++) {
                records.add(new SubRecord(wb.segment.get(BE_INT, recBase), wb.segment.get(BE_LONG, recBase + 4)));
                recBase += 12;
            }
            sum += msgType + seq;
            for (SubRecord rec : records) sum += rec.id() + rec.amount();
        }
        return sum;
    }

    /** Validates bounds once per message, then reads every sub-record directly from the segment — no object graph at all. */
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
        int count = segment.get(BE_INT, base + 5);
        long sum = msgType + seq;
        long recBase = base + 9L;
        for (int r = 0; r < count; r++) {
            sum += segment.get(BE_INT, recBase) + segment.get(BE_LONG, recBase + 4);
            recBase += 12;
        }
        return sum;
    }

    /** Defers frame-bounds validation to first field access. */
    public static long lazyFieldDecode(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            boolean[] validated = {false};
            if (!validated[0]) {
                validateFrame(wb.segment, base);
                validated[0] = true;
            }
            sum += sumViewed(wb.segment, base);
        }
        return sum;
    }

    /** Round-trips the first sub-record's id through an XOR mutation, then sums like validatedZeroCopyView. */
    public static long mutableInPlaceUpdate(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            long firstIdOffset = base + 9L;
            int original = wb.segment.get(BE_INT, firstIdOffset);
            int mutated = original ^ MUTATION_MASK;
            wb.segment.set(BE_INT, firstIdOffset, mutated);
            int readBack = wb.segment.get(BE_INT, firstIdOffset);
            if (readBack != mutated) {
                throw new IllegalStateException("in-place mutation round-trip failed at offset " + firstIdOffset);
            }
            wb.segment.set(BE_INT, firstIdOffset, original);
            sum += sumViewed(wb.segment, base);
        }
        return sum;
    }
}
