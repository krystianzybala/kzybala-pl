package pl.kzybala.lab.arenareuse;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The five lifecycle variants for {@code temporaryParseTrees}: every
 * document is a small, flat {@code long[nodesPerDoc]} scratch container
 * (this lab's array-backed stand-in for a tiny parse tree — the tree's
 * structure is index-implicit, per content/labs/allocation-object-layout's
 * established technique; only its scratch memory's lifecycle differs
 * across variants here). Every variant sums the identical node-value
 * stream.
 */
public final class ParseTreeOperations {

    public static final int POOL_CAPACITY = 64;
    private static final ThreadLocal<long[]> THREAD_LOCAL = new ThreadLocal<>();

    private ParseTreeOperations() {}

    private static long sumSlice(long[] value, long[] scratch, int docOffset, int nodesPerDoc) {
        System.arraycopy(value, docOffset, scratch, 0, nodesPerDoc);
        long sum = 0;
        for (int j = 0; j < nodesPerDoc; j++) sum += scratch[j];
        return sum;
    }

    /** A fresh long[] scratch array per document — GC-eligible immediately after use. */
    public static long allocatePerItem(long[] value, int nodesPerDoc) {
        long sum = 0;
        int docCount = value.length / nodesPerDoc;
        for (int d = 0; d < docCount; d++) {
            long[] scratch = new long[nodesPerDoc];
            sum += sumSlice(value, scratch, d * nodesPerDoc, nodesPerDoc);
        }
        return sum;
    }

    /** One confined Arena per batch of documents; close() at batch end is the measured reset cost. */
    public static long batchArena(long[] value, int nodesPerDoc, int docsPerBatch) {
        long sum = 0;
        int docCount = value.length / nodesPerDoc;
        int d = 0;
        while (d < docCount) {
            int end = Math.min(d + docsPerBatch, docCount);
            int docsInBatch = end - d;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate(8L * nodesPerDoc * docsInBatch, 8);
                for (int i = 0; i < docsInBatch * nodesPerDoc; i++) {
                    segment.set(ValueLayout.JAVA_LONG, i * 8L, value[d * nodesPerDoc + i]);
                }
                for (int i = 0; i < docsInBatch * nodesPerDoc; i++) {
                    sum += segment.get(ValueLayout.JAVA_LONG, i * 8L);
                }
            }
            d = end;
        }
        return sum;
    }

    /** One long[] scratch array per thread, reused for every document for the life of the thread. */
    public static long threadLocalReuse(long[] value, int nodesPerDoc) {
        long[] scratch = THREAD_LOCAL.get();
        if (scratch == null || scratch.length != nodesPerDoc) {
            scratch = new long[nodesPerDoc];
            THREAD_LOCAL.set(scratch);
        }
        long sum = 0;
        int docCount = value.length / nodesPerDoc;
        for (int d = 0; d < docCount; d++) {
            sum += sumSlice(value, scratch, d * nodesPerDoc, nodesPerDoc);
        }
        return sum;
    }

    public static long globalPool(long[] value, int nodesPerDoc, UnboundedPool<long[]> pool) {
        long sum = 0;
        int docCount = value.length / nodesPerDoc;
        for (int d = 0; d < docCount; d++) {
            long[] scratch = pool.borrow();
            sum += sumSlice(value, scratch, d * nodesPerDoc, nodesPerDoc);
            pool.release(scratch);
        }
        return sum;
    }

    public static long boundedPool(long[] value, int nodesPerDoc, BoundedPool<long[]> pool) {
        long sum = 0;
        int docCount = value.length / nodesPerDoc;
        for (int d = 0; d < docCount; d++) {
            long[] scratch = pool.borrow();
            sum += sumSlice(value, scratch, d * nodesPerDoc, nodesPerDoc);
            pool.release(scratch);
        }
        return sum;
    }
}
