package pl.kzybala.lab.falsesharing;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * Per-thread ownership: instead of two threads writing two counters that
 * happen to share a line, every thread owns one shard and nobody else ever
 * writes it. A reader reduces over all shards. This removes the coherence
 * ping-pong at the root — there is nothing to pad against, because no line
 * is ever written by two cores.
 *
 * <p>Layout: shards live in one {@code long[]} with a stride of
 * {@link #STRIDE} longs (64 bytes) per shard, plus one leading and one
 * trailing pad region so shard 0 and shard N-1 do not share a line with the
 * array header or with whatever the allocator places after the array. The
 * 64-byte stride is a documented assumption, not a hardware guarantee — see
 * theory.md.
 *
 * <p>Memory order: each shard has exactly one writer, so the owner does a
 * plain read followed by a {@link VarHandle#setRelease} write — no atomic
 * read-modify-write is needed when there is no write-write race. The
 * reduction reads with {@code getAcquire}. An exact total is only guaranteed
 * after the owner threads have been joined (join gives the happens-before
 * edge); a concurrent {@link #total()} is a monotonic lower-bound snapshot.
 */
public final class ShardedCounters {

    /** 8 longs = 64 bytes: one shard per assumed cache line. */
    static final int STRIDE = 8;

    private static final VarHandle SHARDS =
        MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] shards;
    private final int shardCount;

    public ShardedCounters(int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        this.shardCount = shardCount;
        this.shards = new long[(shardCount + 2) * STRIDE];
    }

    public int shardCount() {
        return shardCount;
    }

    private int index(int shard) {
        return (Objects.checkIndex(shard, shardCount) + 1) * STRIDE;
    }

    /** Owner-only increment — must only ever be called by the shard's owner thread. */
    public void add(int shard, long delta) {
        int i = index(shard);
        long current = (long) SHARDS.get(shards, i);
        SHARDS.setRelease(shards, i, current + delta);
    }

    public long shardValue(int shard) {
        return (long) SHARDS.getAcquire(shards, index(shard));
    }

    /** Reduction over all shards. Exact only after the owners have been joined. */
    public long total() {
        long sum = 0;
        for (int s = 0; s < shardCount; s++) {
            sum += (long) SHARDS.getAcquire(shards, (s + 1) * STRIDE);
        }
        return sum;
    }
}
