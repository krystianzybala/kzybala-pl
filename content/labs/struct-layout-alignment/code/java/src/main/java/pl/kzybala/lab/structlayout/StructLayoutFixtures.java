package pl.kzybala.lab.structlayout;

/**
 * Deterministic source-data generation for the three datasets — the
 * cross-language equivalence contract
 * (../fixtures/struct-layout-alignment-fixtures.json). Every layout
 * variant reads the identical field values for a given dataset; field
 * order and alignment change bytes/record and ns/access, never the
 * checksum (or, for {@code producerConsumerCounters}, the final counts).
 */
public final class StructLayoutFixtures {

    public static final int MIXED_N = 500_000;
    public static final int HEADER_N = 200_000;
    public static final int PAYLOAD_WORDS = 8;
    public static final long COUNTER_INCREMENTS_PER_THREAD = 5_000_000L;

    private StructLayoutFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    // ---- mixedPrimitiveRecord ----

    public static final class MixedSource {
        public final int[] active;
        public final long[] timestamp;
        public final int[] category;
        public final int[] quantity;
        public final int[] flags;
        public final long[] amountTicks;

        MixedSource(int[] active, long[] timestamp, int[] category, int[] quantity, int[] flags, long[] amountTicks) {
            this.active = active;
            this.timestamp = timestamp;
            this.category = category;
            this.quantity = quantity;
            this.flags = flags;
            this.amountTicks = amountTicks;
        }
    }

    public static MixedSource generateMixed(int n) {
        int[] active = new int[n];
        long[] timestamp = new long[n];
        int[] category = new int[n];
        int[] quantity = new int[n];
        int[] flags = new int[n];
        long[] amountTicks = new long[n];
        long x = 90L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            active[i] = (int) Long.remainderUnsigned(x, 2);
            x = xorshift64(x);
            category[i] = (int) Long.remainderUnsigned(x, 16);
            x = xorshift64(x);
            quantity[i] = (int) Long.remainderUnsigned(x, 100_000);
            x = xorshift64(x);
            flags[i] = (int) Long.remainderUnsigned(x, 1_000);
            x = xorshift64(x);
            amountTicks[i] = Long.remainderUnsigned(x, 10_000_000);
            timestamp[i] = i;
        }
        return new MixedSource(active, timestamp, category, quantity, flags, amountTicks);
    }

    public static long expectedMixedChecksum(MixedSource s) {
        long sum = 0;
        for (int i = 0; i < s.active.length; i++) {
            sum += s.active[i] + s.timestamp[i] + s.category[i] + s.quantity[i] + s.flags[i] + s.amountTicks[i];
        }
        return sum;
    }

    // ---- headerPlusPayload ----

    public static final class HeaderSource {
        public final int[] msgType;
        public final int[] msgFlags;
        public final int[] sequence;
        public final long[][] payload; // [frame][word]

        HeaderSource(int[] msgType, int[] msgFlags, int[] sequence, long[][] payload) {
            this.msgType = msgType;
            this.msgFlags = msgFlags;
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    public static HeaderSource generateHeader(int n) {
        int[] msgType = new int[n];
        int[] msgFlags = new int[n];
        int[] sequence = new int[n];
        long[][] payload = new long[n][PAYLOAD_WORDS];
        long x = 91L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            msgType[i] = (int) Long.remainderUnsigned(x, 8);
            x = xorshift64(x);
            msgFlags[i] = (int) Long.remainderUnsigned(x, 256);
            x = xorshift64(x);
            sequence[i] = (int) Long.remainderUnsigned(x, 1_000_000);
            for (int w = 0; w < PAYLOAD_WORDS; w++) {
                x = xorshift64(x);
                payload[i][w] = Long.remainderUnsigned(x, 1_000_000);
            }
        }
        return new HeaderSource(msgType, msgFlags, sequence, payload);
    }

    public static long expectedHeaderChecksum(HeaderSource s) {
        long sum = 0;
        for (int i = 0; i < s.msgType.length; i++) {
            sum += s.msgType[i] + s.msgFlags[i] + s.sequence[i];
            for (long v : s.payload[i]) sum += v;
        }
        return sum;
    }
}
