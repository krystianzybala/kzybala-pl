package pl.kzybala.lab.observability;

/** The business logic being observed — a pure function of `id`, identical under every variant. */
public final class HotPath {
    private HotPath() {}

    public static long work(int id) {
        long x = (id * 0x9E3779B97F4A7C15L) + 1L;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /** A call stack a few frames deep, for the "stack trace path" dataset's sampled-tracing variant. */
    public static long workThroughStack(int id, int depth) {
        if (depth <= 0) {
            return work(id);
        }
        return workThroughStack(id, depth - 1);
    }
}
