package pl.kzybala.lab.spscringbuffer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Companion code for the "SPSC Ring Buffer" Performance Lab
 * (kzybala.pl/lab/spsc-ring-buffer/). See java.md for the full explanation.
 *
 * A bounded single-producer/single-consumer ring buffer of primitive
 * {@code long} values — zero allocation on the {@link #tryProduce(long)}/
 * {@link #tryConsume(long[])} hot path. Capacity MUST be a power of two so
 * the slot index is a cheap {@code & mask} instead of {@code % capacity}.
 *
 * Ownership discipline, not atomics, is what makes this correct: exactly
 * one thread may ever call {@link #tryProduce(long)}, exactly one thread
 * (which may differ from the first) may ever call
 * {@link #tryConsume(long[])}. {@code head}/{@code tail} use VarHandle
 * release/acquire so a payload written before publication is guaranteed
 * visible to the consumer once it observes the published head (see the
 * Memory Ordering lab) — {@code cachedTail}/{@code cachedHead} are plain
 * fields, touched only by their owning thread, so they need no
 * synchronization at all.
 */
public final class SpscRingBuffer {
    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
        try {
            HEAD = MethodHandles.lookup().findVarHandle(SpscRingBuffer.class, "head", long.class);
            TAIL = MethodHandles.lookup().findVarHandle(SpscRingBuffer.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final long[] slots;
    private final int mask;

    // Published cursor: producer releases after writing a payload, consumer
    // acquires before reading one. Accessed only through HEAD (VarHandle).
    private volatile long head = 0;
    // Acknowledged cursor: consumer releases after reading a payload,
    // producer acquires before reserving a slot. Accessed only through TAIL.
    private volatile long tail = 0;

    // Producer-only cache of the real tail — avoids an acquire-load on every
    // reservation; only refreshed when the cached view suggests "full".
    private long cachedTail = 0;
    private long reserveIndex = 0;

    // Consumer-only cache of the real head — avoids an acquire-load on every
    // read; only refreshed when the cached view suggests "empty".
    private long cachedHead = 0;
    private long readIndex = 0;

    public SpscRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two, was " + capacity);
        }
        this.slots = new long[capacity];
        this.mask = capacity - 1;
    }

    /**
     * Producer-only: reserves a slot, writes {@code value} into it, then
     * publishes. Returns {@code false} (rejected) rather than overwriting a
     * slot the consumer has not yet acknowledged.
     */
    public boolean tryProduce(long value) {
        int capacity = slots.length;
        if (reserveIndex - cachedTail == capacity) {
            cachedTail = (long) TAIL.getAcquire(this);
            if (reserveIndex - cachedTail == capacity) {
                return false; // genuinely full
            }
        }
        slots[(int) (reserveIndex & mask)] = value; // payload write — not yet visible
        reserveIndex++;
        HEAD.setRelease(this, reserveIndex); // publication
        return true;
    }

    /**
     * Consumer-only: reads and acknowledges the next slot into {@code out[0]}.
     * Returns {@code false} (nothing available) rather than reading
     * unpublished slot content. {@code out} avoids boxing a return value.
     */
    public boolean tryConsume(long[] out) {
        if (readIndex == cachedHead) {
            cachedHead = (long) HEAD.getAcquire(this);
            if (readIndex == cachedHead) {
                return false; // genuinely empty
            }
        }
        out[0] = slots[(int) (readIndex & mask)]; // payload read
        readIndex++;
        TAIL.setRelease(this, readIndex); // consumption acknowledgement
        return true;
    }
}
