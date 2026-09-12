package pl.kzybala.lab.arenareuse;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static pl.kzybala.lab.arenareuse.ArenaReuseFixtures.MessageSource;

/**
 * The five lifecycle variants for {@code messageBatches}: every
 * variant decodes the identical (id, value, flag) stream into a scratch
 * holder, reads it back, and sums — only how the scratch memory is
 * obtained, reused and released differs.
 */
public final class MessageBatchOperations {

    public static final int POOL_CAPACITY = 64;
    private static final long STRIDE = 24; // id(8) + value(8) + flag(4) + pad(4)

    private static final ThreadLocal<MessageScratch> THREAD_LOCAL = ThreadLocal.withInitial(MessageScratch::new);

    private MessageBatchOperations() {}

    /** A fresh MessageScratch per message — GC-eligible the instant the loop body ends. */
    public static long allocatePerItem(MessageSource s) {
        long sum = 0;
        for (int i = 0; i < s.value.length; i++) {
            MessageScratch m = new MessageScratch();
            m.id = i;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum += m.id + m.value + m.flag;
        }
        return sum;
    }

    /** One confined Arena per batch: bump-allocate the whole batch's records, then close() — the measured "reset/close cost". */
    public static long batchArena(MessageSource s, int batchSize) {
        long sum = 0;
        int n = s.value.length;
        int i = 0;
        while (i < n) {
            int end = Math.min(i + batchSize, n);
            int count = end - i;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = arena.allocate(STRIDE * count, 8);
                for (int j = 0; j < count; j++) {
                    long base = j * STRIDE;
                    segment.set(ValueLayout.JAVA_LONG, base, i + j);
                    segment.set(ValueLayout.JAVA_LONG, base + 8, s.value[i + j]);
                    segment.set(ValueLayout.JAVA_INT, base + 16, s.flag[i + j]);
                }
                for (int j = 0; j < count; j++) {
                    long base = j * STRIDE;
                    sum += segment.get(ValueLayout.JAVA_LONG, base)
                        + segment.get(ValueLayout.JAVA_LONG, base + 8)
                        + segment.get(ValueLayout.JAVA_INT, base + 16);
                }
            } // arena.close() here, per batch, not per message
            i = end;
        }
        return sum;
    }

    /** One MessageScratch per thread, reused for every message for the life of the thread — never reallocated, never released. */
    public static long threadLocalReuse(MessageSource s) {
        MessageScratch m = THREAD_LOCAL.get();
        long sum = 0;
        for (int i = 0; i < s.value.length; i++) {
            m.id = i;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum += m.id + m.value + m.flag;
        }
        return sum;
    }

    /** Borrow from (and return to) a shared, unbounded pool for every message. */
    public static long globalPool(MessageSource s, UnboundedPool<MessageScratch> pool) {
        long sum = 0;
        for (int i = 0; i < s.value.length; i++) {
            MessageScratch m = pool.borrow();
            m.id = i;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum += m.id + m.value + m.flag;
            pool.release(m);
        }
        return sum;
    }

    /** Borrow from (and return to) a shared, capacity-bounded pool for every message. */
    public static long boundedPool(MessageSource s, BoundedPool<MessageScratch> pool) {
        long sum = 0;
        for (int i = 0; i < s.value.length; i++) {
            MessageScratch m = pool.borrow();
            m.id = i;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum += m.id + m.value + m.flag;
            pool.release(m);
        }
        return sum;
    }
}
