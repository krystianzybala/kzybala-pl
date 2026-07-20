package pl.kzybala.lab.clockslatency;

/**
 * Deterministic synthetic latency streams — the cross-language equivalence
 * contract (code/fixtures/clocks-latency-histograms-fixtures.json). Every
 * value is derived from the repository-canonical xorshift64 stream with
 * pure integer arithmetic, so Java and Rust produce bit-identical
 * sequences, histograms and percentile fixtures.
 *
 * These are MODELED latencies for teaching distribution mechanics
 * (recording, coordinated omission, merging) deterministically; measured
 * timer/recording costs come only from the harnesses and benchmarks.
 */
public final class SyntheticLatency {

    private SyntheticLatency() {}

    public static long xorshift64(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    /**
     * Bimodal service time in nanoseconds from one stream value:
     * 95% fast mode 800–1199 ns, 5% slow mode 40000–59999 ns
     * (unsigned modulo so both languages agree on every value).
     */
    public static long bimodalNanos(long streamValue) {
        long mode = Long.remainderUnsigned(streamValue, 100);
        if (mode < 95) {
            return 800 + Long.remainderUnsigned(streamValue, 400);
        }
        return 40_000 + Long.remainderUnsigned(streamValue, 20_000);
    }

    /** The n-value bimodal sequence from a seed. */
    public static long[] bimodalSequence(long seed, int n) {
        long[] out = new long[n];
        long x = seed;
        for (int i = 0; i < n; i++) {
            x = xorshift64(x);
            out[i] = bimodalNanos(x);
        }
        return out;
    }

    /**
     * The pause-injection dataset: the bimodal sequence with a 5 ms stall
     * added to every 1000th operation (indices 999, 1999, ...) — the
     * deterministic model of a periodically stalling system.
     */
    public static long[] pauseInjectedSequence(long seed, int n) {
        long[] out = bimodalSequence(seed, n);
        for (int i = 999; i < n; i += 1000) {
            out[i] += 5_000_000L;
        }
        return out;
    }

    /** Wrapping checksum over a sequence (checksum*31 + value) — fixture oracle. */
    public static long checksum(long[] values) {
        long c = 0;
        for (long v : values) {
            c = c * 31 + v;
        }
        return c;
    }
}
