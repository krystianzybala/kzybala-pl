package pl.kzybala.lab.arenareuse;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Supplier;

/**
 * A bounded object pool: {@code borrow()} behaves like
 * {@link UnboundedPool}, but {@code release()} silently DROPS the
 * returned instance once the pool is already at {@code capacity} — the
 * dropped instance simply becomes garbage rather than growing the
 * cache. This is the safe alternative to {@link UnboundedPool}'s
 * "unbounded pools" trap: memory footprint is bounded by
 * {@code capacity} regardless of burst size, at the cost of falling
 * back to fresh allocation whenever demand exceeds the bound.
 */
public final class BoundedPool<T> {

    private final ArrayBlockingQueue<T> cache;
    private final Supplier<T> factory;

    public BoundedPool(int capacity, Supplier<T> factory) {
        this.cache = new ArrayBlockingQueue<>(capacity);
        this.factory = factory;
    }

    public T borrow() {
        T t = cache.poll();
        return t != null ? t : factory.get();
    }

    public void release(T t) {
        cache.offer(t); // no-op (drops t) if the queue is already full
    }

    public int cachedCount() {
        return cache.size();
    }
}
