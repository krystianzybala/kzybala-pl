package pl.kzybala.lab.arenareuse;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The five lifecycle variants for {@code scratchBuffers}: every op
 * writes {@code wordsPerOp} longs into a raw scratch byte buffer (as if
 * encoding a small message), then reads them back and sums — a genuine
 * encode/decode round trip through bytes, not just a value copy. Only
 * how the byte buffer is obtained, reused and released differs.
 */
public final class ScratchBufferOperations {

    public static final int POOL_CAPACITY = 64;
    private static final ThreadLocal<byte[]> THREAD_LOCAL = new ThreadLocal<>();

    private ScratchBufferOperations() {}

    private static long roundTrip(byte[] buf, long[] word, int opOffset, int wordsPerOp) {
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        for (int w = 0; w < wordsPerOp; w++) {
            bb.putLong(w * 8, word[opOffset + w]);
        }
        long sum = 0;
        for (int w = 0; w < wordsPerOp; w++) {
            sum += bb.getLong(w * 8);
        }
        return sum;
    }

    /** A fresh byte[] scratch buffer per op — GC-eligible immediately after use. */
    public static long allocatePerItem(long[] word, int wordsPerOp) {
        long sum = 0;
        int opCount = word.length / wordsPerOp;
        for (int o = 0; o < opCount; o++) {
            byte[] buf = new byte[wordsPerOp * 8];
            sum += roundTrip(buf, word, o * wordsPerOp, wordsPerOp);
        }
        return sum;
    }

    /** One confined Arena per batch of ops; close() at batch end is the measured reset cost. */
    public static long batchArena(long[] word, int wordsPerOp, int opsPerBatch) {
        long sum = 0;
        int opCount = word.length / wordsPerOp;
        int o = 0;
        while (o < opCount) {
            int end = Math.min(o + opsPerBatch, opCount);
            int opsInBatch = end - o;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate(8L * wordsPerOp * opsInBatch, 8);
                for (int i = 0; i < opsInBatch * wordsPerOp; i++) {
                    segment.set(ValueLayout.JAVA_LONG, i * 8L, word[o * wordsPerOp + i]);
                }
                for (int i = 0; i < opsInBatch * wordsPerOp; i++) {
                    sum += segment.get(ValueLayout.JAVA_LONG, i * 8L);
                }
            }
            o = end;
        }
        return sum;
    }

    /** One byte[] scratch buffer per thread, reused for every op for the life of the thread. */
    public static long threadLocalReuse(long[] word, int wordsPerOp) {
        byte[] buf = THREAD_LOCAL.get();
        if (buf == null || buf.length != wordsPerOp * 8) {
            buf = new byte[wordsPerOp * 8];
            THREAD_LOCAL.set(buf);
        }
        long sum = 0;
        int opCount = word.length / wordsPerOp;
        for (int o = 0; o < opCount; o++) {
            sum += roundTrip(buf, word, o * wordsPerOp, wordsPerOp);
        }
        return sum;
    }

    public static long globalPool(long[] word, int wordsPerOp, UnboundedPool<byte[]> pool) {
        long sum = 0;
        int opCount = word.length / wordsPerOp;
        for (int o = 0; o < opCount; o++) {
            byte[] buf = pool.borrow();
            sum += roundTrip(buf, word, o * wordsPerOp, wordsPerOp);
            pool.release(buf);
        }
        return sum;
    }

    public static long boundedPool(long[] word, int wordsPerOp, BoundedPool<byte[]> pool) {
        long sum = 0;
        int opCount = word.length / wordsPerOp;
        for (int o = 0; o < opCount; o++) {
            byte[] buf = pool.borrow();
            sum += roundTrip(buf, word, o * wordsPerOp, wordsPerOp);
            pool.release(buf);
        }
        return sum;
    }
}
