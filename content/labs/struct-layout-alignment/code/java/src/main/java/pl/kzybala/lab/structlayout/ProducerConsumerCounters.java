package pl.kzybala.lab.structlayout;

/**
 * The five layout variants for {@code producerConsumerCounters} — two
 * independent counters, one dedicated writer thread each (no data race;
 * see content/labs/false-sharing, this lab's direct mechanism
 * ancestor). Nothing about correctness differs across variants: each
 * counter's final value after N increments from its own thread is
 * exactly N regardless of layout. What differs is coherence traffic —
 * {@link #Natural} and {@link #PoorFieldOrder} keep the two hot counters
 * within one cache line's reach; {@link #OptimizedFieldOrder}
 * demonstrates that GROUPING hot fields together (normally a good idea
 * for read locality) does NOT by itself fix false sharing between
 * fields two different threads WRITE — only explicit separation
 * ({@link #CacheLineAligned}) does that; {@link #PackedUnaligned} packs
 * both counters into a single 8-byte word, the worst case.
 */
public final class ProducerConsumerCounters {

    private ProducerConsumerCounters() {}

    /** producerCount@0, consumerCount@8 — adjacent, same cache line on typical hosts. */
    public static final class Natural {
        public volatile long producerCount;
        public volatile long consumerCount;
    }

    /** producerCount, three unrelated cold ints, consumerCount — cold fields too small to separate the counters onto different lines. */
    @SuppressWarnings("unused")
    public static final class PoorFieldOrder {
        public volatile long producerCount;
        public int coldA;
        public int coldB;
        public int coldC;
        public volatile long consumerCount;
    }

    /** Hot fields grouped together (good for read locality), cold fields moved after — STILL adjacent, so STILL false-shares. */
    @SuppressWarnings("unused")
    public static final class OptimizedFieldOrder {
        public volatile long producerCount;
        public volatile long consumerCount;
        public int coldA;
        public int coldB;
    }

    /** Manual padding: 7 unused longs (56 bytes) between the two counters, matching content/labs/false-sharing's PaddedCounters technique exactly. */
    @SuppressWarnings("unused")
    public static final class CacheLineAligned {
        public volatile long producerCount;
        public long p1, p2, p3, p4, p5, p6, p7;
        public volatile long consumerCount;
    }

    /** Both counters packed into adjacent 4-byte ints in a single 8-byte word — the worst case: guaranteed same cache line, likely the same word. */
    public static final class PackedUnaligned {
        public volatile int producerCount;
        public volatile int consumerCount;
    }
}
