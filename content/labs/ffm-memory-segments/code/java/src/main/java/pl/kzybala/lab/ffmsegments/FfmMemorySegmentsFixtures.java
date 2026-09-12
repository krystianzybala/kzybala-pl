package pl.kzybala.lab.ffmsegments;

/**
 * Deterministic source-data generation for the three datasets — the
 * cross-language equivalence contract
 * (../fixtures/ffm-memory-segments-fixtures.json). Every storage variant
 * reads the identical field values for a given dataset; storage location
 * and access path change ns/access and ns/record, never the checksum.
 */
public final class FfmMemorySegmentsFixtures {

    public static final int FIXED_RECORDS_N = 500_000;
    public static final int LARGE_BUFFER_N = 5_000_000;
    public static final int BINARY_FRAMES_N = 100_000;
    public static final int PAYLOAD_WORDS = 6;
    public static final int FRAME_LENGTH_FIELD = 48;

    private FfmMemorySegmentsFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    // ---- fixedRecords ----

    public static final class FixedRecordsSource {
        public final long[] id;
        public final long[] value;
        public final int[] flag;

        FixedRecordsSource(long[] id, long[] value, int[] flag) {
            this.id = id;
            this.value = value;
            this.flag = flag;
        }
    }

    public static FixedRecordsSource generateFixedRecords(int n) {
        long[] id = new long[n];
        long[] value = new long[n];
        int[] flag = new int[n];
        long x = 80L;
        for (int i = 0; i < n; i++) {
            id[i] = i;
            x = xorshift64(x);
            value[i] = Long.remainderUnsigned(x, 1_000_000);
            x = xorshift64(x);
            flag[i] = (int) Long.remainderUnsigned(x, 8);
        }
        return new FixedRecordsSource(id, value, flag);
    }

    public static long expectedFixedRecordsChecksum(FixedRecordsSource s) {
        long sum = 0;
        for (int i = 0; i < s.id.length; i++) sum += s.id[i] + s.value[i] + s.flag[i];
        return sum;
    }

    // ---- largeNumericBuffers ----

    public static long[] generateLargeBuffer(int n) {
        long[] value = new long[n];
        long x = 81L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            value[i] = Long.remainderUnsigned(x, 1_000_000_000);
        }
        return value;
    }

    public static long expectedLargeBufferChecksum(long[] value) {
        long sum = 0;
        for (long v : value) sum += v;
        return sum;
    }

    // ---- binaryFrames ----

    public static final class BinaryFramesSource {
        public final int[] type;
        public final long[][] payload; // [frame][word]

        BinaryFramesSource(int[] type, long[][] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    public static BinaryFramesSource generateBinaryFrames(int n) {
        int[] type = new int[n];
        long[][] payload = new long[n][PAYLOAD_WORDS];
        long x = 82L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            type[i] = (int) Long.remainderUnsigned(x, 4);
            for (int w = 0; w < PAYLOAD_WORDS; w++) {
                x = xorshift64(x);
                payload[i][w] = Long.remainderUnsigned(x, 1_000_000);
            }
        }
        return new BinaryFramesSource(type, payload);
    }

    public static long expectedBinaryFramesChecksum(BinaryFramesSource s) {
        long sum = 0;
        for (int i = 0; i < s.type.length; i++) {
            sum += FRAME_LENGTH_FIELD + s.type[i];
            for (long v : s.payload[i]) sum += v;
        }
        return sum;
    }

    /** K deterministic pseudo-random indices in [0, n) — identical stream shared by every variant. */
    public static int[] randomIndices(int n, int k, long seed) {
        int[] indices = new int[k];
        long x = seed;
        for (int i = 0; i < k; i++) {
            x = xorshift64(x);
            indices[i] = (int) Long.remainderUnsigned(x, n);
        }
        return indices;
    }
}
