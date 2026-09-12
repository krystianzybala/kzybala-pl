package pl.kzybala.lab.zerocopy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static pl.kzybala.lab.zerocopy.ZeroCopyFixtures.WireBuffer;

/**
 * The five decoder variants for {@code utf8Field} — a message carrying
 * one variable-length UTF-8 text field. Every variant reads the
 * identical big-endian wire bytes and reproduces the identical checksum
 * (this dataset's generated text is always printable ASCII, so summing
 * raw byte values and summing decoded char codes are numerically
 * identical — the checksum genuinely does not require ever materializing
 * a {@code String}). This dataset is where this lab's "claiming
 * zero-copy while converting strings" trap is made concrete:
 * {@link #validatedZeroCopyView} and {@link #lazyFieldDecode} NEVER call
 * {@code new String(...)} for the checksum operation — only
 * {@link #copyingDecoder} and {@link #objectBuildingDecoder} do.
 */
public final class Utf8FieldDecoders {

    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final byte MUTATION_MASK = 0x01;

    private Utf8FieldDecoders() {}

    private static void validateFrame(MemorySegment segment, int base) {
        if (base < 0 || base + 9 > segment.byteSize()) {
            throw new IllegalStateException("frame header out of bounds at offset " + base);
        }
        int len = segment.get(BE_INT, base + 5);
        long payloadEnd = base + 9L + len;
        if (len < 0 || payloadEnd > segment.byteSize()) {
            throw new IllegalStateException("frame payload out of bounds at offset " + base);
        }
    }

    /** Materializes a real, independently-owned java.lang.String — a genuine copy and UTF-8 decode. */
    public static long copyingDecoder(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int len = wb.segment.get(BE_INT, base + 5);
            byte[] raw = new byte[len];
            MemorySegment.copy(wb.segment, ValueLayout.JAVA_BYTE, base + 9L, raw, 0, len);
            String text = new String(raw, StandardCharsets.UTF_8);
            sum += msgType + seq;
            for (int c = 0; c < text.length(); c++) sum += text.charAt(c);
        }
        return sum;
    }

    public record DecodedTextMessage(int msgType, int seq, String text) {}

    /** Wraps the materialized String in a small record object — one more allocation layer than copyingDecoder. */
    public static long objectBuildingDecoder(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int len = wb.segment.get(BE_INT, base + 5);
            byte[] raw = new byte[len];
            MemorySegment.copy(wb.segment, ValueLayout.JAVA_BYTE, base + 9L, raw, 0, len);
            DecodedTextMessage message = new DecodedTextMessage(msgType, seq, new String(raw, StandardCharsets.UTF_8));
            sum += message.msgType() + message.seq();
            String text = message.text();
            for (int c = 0; c < text.length(); c++) sum += text.charAt(c);
        }
        return sum;
    }

    /**
     * Validates UTF-8 validity ONCE (a real check, never skipped — this
     * lab's "skipping validation"/"using invalid UTF-8 unchecked" traps),
     * then sums raw byte values directly from the segment. No
     * {@code String}, no {@code CharSequence}, no copy — the checksum
     * this dataset requires never needs one.
     */
    public static long validatedZeroCopyView(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int len = wb.segment.get(BE_INT, base + 5);
            if (!ZeroCopyFixtures.isValidUtf8(wb.segment, base + 9L, len)) {
                throw new IllegalStateException("invalid UTF-8 at offset " + (base + 9));
            }
            sum += sumViewed(wb.segment, base, len);
        }
        return sum;
    }

    private static long sumViewed(MemorySegment segment, int base, int len) {
        int msgType = segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
        int seq = segment.get(BE_INT, base + 1);
        long sum = msgType + seq;
        for (int j = 0; j < len; j++) {
            sum += segment.get(ValueLayout.JAVA_BYTE, base + 9 + j) & 0xFF;
        }
        return sum;
    }

    /** Defers the UTF-8 validity check to first field access — still a real check, just deferred, never skipped. */
    public static long lazyFieldDecode(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int len = wb.segment.get(BE_INT, base + 5);
            boolean[] validated = {false};
            if (!validated[0]) {
                if (!ZeroCopyFixtures.isValidUtf8(wb.segment, base + 9L, len)) {
                    throw new IllegalStateException("invalid UTF-8 at offset " + (base + 9));
                }
                validated[0] = true;
            }
            sum += sumViewed(wb.segment, base, len);
        }
        return sum;
    }

    /** Round-trips the text's first byte through an XOR mutation (flip low bit, verify, restore), then sums like validatedZeroCopyView. */
    public static long mutableInPlaceUpdate(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            validateFrame(wb.segment, base);
            int len = wb.segment.get(BE_INT, base + 5);
            long firstByteOffset = base + 9L;
            byte original = wb.segment.get(ValueLayout.JAVA_BYTE, firstByteOffset);
            byte mutated = (byte) (original ^ MUTATION_MASK);
            wb.segment.set(ValueLayout.JAVA_BYTE, firstByteOffset, mutated);
            byte readBack = wb.segment.get(ValueLayout.JAVA_BYTE, firstByteOffset);
            if (readBack != mutated) {
                throw new IllegalStateException("in-place mutation round-trip failed at offset " + firstByteOffset);
            }
            wb.segment.set(ValueLayout.JAVA_BYTE, firstByteOffset, original);
            sum += sumViewed(wb.segment, base, len);
        }
        return sum;
    }
}
