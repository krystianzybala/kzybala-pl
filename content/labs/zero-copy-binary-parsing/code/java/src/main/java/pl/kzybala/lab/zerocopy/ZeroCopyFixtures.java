package pl.kzybala.lab.zerocopy;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Deterministic source-data generation AND wire-format encoding for the
 * three datasets — the cross-language equivalence contract
 * (../fixtures/zero-copy-binary-parsing-fixtures.json). Every frame is
 * big-endian (network byte order): {@code msgType}(1B) +
 * {@code seq}(4B BE u32) + a dataset-specific length/count field(4B BE
 * u32) + payload(variable). Every decoder variant reads the identical
 * encoded bytes and reproduces the identical checksum; decoding strategy
 * changes ns/message, B/message, bytes copied and validation cost, never
 * the result.
 */
public final class ZeroCopyFixtures {

    private static final ValueLayout.OfInt BE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong BE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN);

    private ZeroCopyFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /** The encoded wire buffer for a dataset, plus each message's starting byte offset. */
    public static final class WireBuffer {
        public final MemorySegment segment;
        public final int[] frameOffsets; // frameOffsets[i] = byte offset of message i's header

        WireBuffer(MemorySegment segment, int[] frameOffsets) {
            this.segment = segment;
            this.frameOffsets = frameOffsets;
        }
    }

    // ---- fixedHeaderPlusVariablePayload ----

    public static final int FIXED_HEADER_N = 200_000;
    private static final int[] PAYLOAD_WORD_CYCLE = {4, 8, 16, 32};

    public static WireBuffer encodeFixedHeader(int n) {
        int total = 0;
        long x = 110L;
        int[] offsets = new int[n];
        // First pass: sizes only, to size the buffer (cheap re-derivation, not counted as decode cost).
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            int words = PAYLOAD_WORD_CYCLE[i % 4];
            total += 9 + words * 8;
        }
        MemorySegment segment = MemorySegment.ofArray(new byte[total]);
        for (int i = 0; i < n; i++) {
            int base = offsets[i];
            int words = PAYLOAD_WORD_CYCLE[i % 4];
            segment.set(ValueLayout.JAVA_BYTE, base, (byte) (i % 4));
            segment.set(BE_INT, base + 1, i);
            segment.set(BE_INT, base + 5, words);
            for (int w = 0; w < words; w++) {
                x = xorshift64(x);
                long v = Long.remainderUnsigned(x, 1_000_000);
                segment.set(BE_LONG, base + 9 + w * 8L, v);
            }
        }
        return new WireBuffer(segment, offsets);
    }

    public static long expectedFixedHeaderChecksum(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int words = wb.segment.get(BE_INT, base + 5);
            sum += msgType + seq;
            for (int w = 0; w < words; w++) {
                sum += wb.segment.get(BE_LONG, base + 9 + w * 8L);
            }
        }
        return sum;
    }

    // ---- nestedRepeatedFields ----

    public static final int NESTED_N = 150_000;
    private static final int[] RECORD_COUNT_CYCLE = {2, 4, 8};

    public static WireBuffer encodeNested(int n) {
        int total = 0;
        int[] offsets = new int[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            int count = RECORD_COUNT_CYCLE[i % 3];
            total += 9 + count * 12;
        }
        MemorySegment segment = MemorySegment.ofArray(new byte[total]);
        long x = 111L;
        for (int i = 0; i < n; i++) {
            int base = offsets[i];
            int count = RECORD_COUNT_CYCLE[i % 3];
            segment.set(ValueLayout.JAVA_BYTE, base, (byte) (i % 4));
            segment.set(BE_INT, base + 1, i);
            segment.set(BE_INT, base + 5, count);
            long recBase = base + 9;
            for (int r = 0; r < count; r++) {
                x = xorshift64(x);
                int id = (int) Long.remainderUnsigned(x, 1_000_000);
                x = xorshift64(x);
                long amount = Long.remainderUnsigned(x, 10_000_000);
                segment.set(BE_INT, recBase, id);
                segment.set(BE_LONG, recBase + 4, amount);
                recBase += 12;
            }
        }
        return new WireBuffer(segment, offsets);
    }

    public static long expectedNestedChecksum(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int count = wb.segment.get(BE_INT, base + 5);
            sum += msgType + seq;
            long recBase = base + 9;
            for (int r = 0; r < count; r++) {
                int id = wb.segment.get(BE_INT, recBase);
                long amount = wb.segment.get(BE_LONG, recBase + 4);
                sum += id + amount;
                recBase += 12;
            }
        }
        return sum;
    }

    // ---- utf8Field ----

    public static final int UTF8_N = 150_000;
    private static final int[] TEXT_LEN_CYCLE = {8, 16, 32, 64};

    public static WireBuffer encodeUtf8(int n) {
        int total = 0;
        int[] offsets = new int[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            int len = TEXT_LEN_CYCLE[i % 4];
            total += 9 + len;
        }
        MemorySegment segment = MemorySegment.ofArray(new byte[total]);
        long x = 112L;
        for (int i = 0; i < n; i++) {
            int base = offsets[i];
            int len = TEXT_LEN_CYCLE[i % 4];
            segment.set(ValueLayout.JAVA_BYTE, base, (byte) (i % 4));
            segment.set(BE_INT, base + 1, i);
            segment.set(BE_INT, base + 5, len);
            for (int j = 0; j < len; j++) {
                x = xorshift64(x);
                int b = 0x20 + (int) Long.remainderUnsigned(x, 95);
                segment.set(ValueLayout.JAVA_BYTE, base + 9 + j, (byte) b);
            }
        }
        return new WireBuffer(segment, offsets);
    }

    public static long expectedUtf8Checksum(WireBuffer wb, int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int base = wb.frameOffsets[i];
            int msgType = wb.segment.get(ValueLayout.JAVA_BYTE, base) & 0xFF;
            int seq = wb.segment.get(BE_INT, base + 1);
            int len = wb.segment.get(BE_INT, base + 5);
            sum += msgType + seq;
            for (int j = 0; j < len; j++) {
                sum += wb.segment.get(ValueLayout.JAVA_BYTE, base + 9 + j) & 0xFF;
            }
        }
        return sum;
    }

    /** Real, honest UTF-8 validation — used by validatedZeroCopyView/lazyFieldDecode, never skipped. */
    public static boolean isValidUtf8(MemorySegment segment, long offset, int length) {
        byte[] tmp = new byte[length];
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, offset, tmp, 0, length);
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(tmp));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }
}
