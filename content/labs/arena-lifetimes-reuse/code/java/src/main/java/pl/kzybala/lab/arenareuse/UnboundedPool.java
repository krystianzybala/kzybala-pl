package pl.kzybala.lab.arenareuse;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

/**
 * An unbounded object pool: {@code borrow()} takes a cached instance if
 * one is available, or allocates a fresh one via {@code factory}
 * otherwise; {@code release()} always accepts the instance back,
 * regardless of how many are already cached. This is deliberately
 * unbounded — this lab's "unbounded pools" trap names exactly the
 * failure mode this class can exhibit under bursty load (borrow rate
 * temporarily exceeding return rate lets the cache grow without limit,
 * retaining memory the workload no longer needs).
 */
public final class UnboundedPool<T> {

    private final ConcurrentLinkedDeque<T> cache = new ConcurrentLinkedDeque<>();
    private final Supplier<T> factory;

    public UnboundedPool(Supplier<T> factory) {
        this.factory = factory;
    }

    public T borrow() {
        T t = cache.poll();
        return t != null ? t : factory.get();
    }

    public void release(T t) {
        cache.push(t);
    }

    public int cachedCount() {
        return cache.size();
    }
}
