package pl.kzybala.lab.arenareuse;

/**
 * Deterministic source-data generation for the three datasets — the
 * cross-language equivalence contract
 * (../fixtures/arena-lifetimes-reuse-fixtures.json). Every lifecycle
 * variant reads the identical field values for a given dataset;
 * allocation strategy changes B/op, allocations/op, reset/close cost and
 * contention, never the checksum.
 */
public final class ArenaReuseFixtures {

    public static final int MESSAGE_N = 500_000;
    public static final int MESSAGE_BATCH_SIZE = 500;

    public static final int DOC_COUNT = 100_000;
    public static final int NODES_PER_DOC = 8;
    public static final int DOCS_PER_BATCH = 100;

    public static final int BUFFER_OP_COUNT = 200_000;
    public static final int WORDS_PER_OP = 8;
    public static final int OPS_PER_BATCH = 200;

    private ArenaReuseFixtures() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    // ---- messageBatches ----

    public static final class MessageSource {
        public final long[] value;
        public final int[] flag;

        MessageSource(long[] value, int[] flag) {
            this.value = value;
            this.flag = flag;
        }
    }

    public static MessageSource generateMessages(int n) {
        long[] value = new long[n];
        int[] flag = new int[n];
        long x = 100L;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            value[i] = Long.remainderUnsigned(x, 1_000_000);
            x = xorshift64(x);
            flag[i] = (int) Long.remainderUnsigned(x, 16);
        }
        return new MessageSource(value, flag);
    }

    public static long expectedMessagesChecksum(MessageSource s) {
        long sum = 0;
        for (int i = 0; i < s.value.length; i++) {
            sum += i + s.value[i] + s.flag[i];
        }
        return sum;
    }

    // ---- temporaryParseTrees ----

    /** Flat, row-major: value[doc * nodesPerDoc + node]. */
    public static long[] generateTrees(int docCount, int nodesPerDoc) {
        long[] value = new long[docCount * nodesPerDoc];
        long x = 101L;
        for (int i = 0; i < value.length; i++) {
            x = xorshift64(x);
            value[i] = Long.remainderUnsigned(x, 1_000_000);
        }
        return value;
    }

    public static long expectedTreesChecksum(long[] value) {
        long sum = 0;
        for (long v : value) sum += v;
        return sum;
    }

    // ---- scratchBuffers ----

    /** Flat, row-major: word[op * wordsPerOp + w]. */
    public static long[] generateBuffers(int opCount, int wordsPerOp) {
        long[] word = new long[opCount * wordsPerOp];
        long x = 102L;
        for (int i = 0; i < word.length; i++) {
            x = xorshift64(x);
            word[i] = Long.remainderUnsigned(x, 1_000_000);
        }
        return word;
    }

    public static long expectedBuffersChecksum(long[] word) {
        long sum = 0;
        for (long v : word) sum += v;
        return sum;
    }
}
