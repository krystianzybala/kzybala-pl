package pl.kzybala.lab.clockslatency;

import org.HdrHistogram.Histogram;

/**
 * The lab's histogram conventions, defined once: fixed dynamic range
 * (1 ns .. 60 s) at 3 significant digits — the resolution/footprint
 * trade-off benchmark.md discusses. Recording is allocation-free after
 * construction; construction always happens outside any timed region.
 */
public final class LatencyHistograms {

    public static final long LOWEST_DISCERNIBLE = 1;
    public static final long HIGHEST_TRACKABLE = 60_000_000_000L;
    public static final int SIGNIFICANT_DIGITS = 3;

    private LatencyHistograms() {}

    public static Histogram newHistogram() {
        return new Histogram(LOWEST_DISCERNIBLE, HIGHEST_TRACKABLE, SIGNIFICANT_DIGITS);
    }

    /** Naive recording: one value per completed operation, service time only. */
    public static Histogram recordAll(long[] values) {
        Histogram h = newHistogram();
        for (long v : values) {
            h.recordValue(v);
        }
        return h;
    }

    /**
     * Coordinated-omission-corrected recording: each value is recorded with
     * the expected arrival interval, so a stalled operation also accounts
     * for the arrivals it blocked (HdrHistogram's standard correction).
     */
    public static Histogram recordCorrected(long[] values, long expectedIntervalNanos) {
        Histogram h = newHistogram();
        for (long v : values) {
            h.recordValueWithExpectedInterval(v, expectedIntervalNanos);
        }
        return h;
    }

    /** Sampled recording: every {@code sampleEvery}-th value only. */
    public static Histogram recordSampled(long[] values, int sampleEvery) {
        Histogram h = newHistogram();
        for (int i = 0; i < values.length; i += sampleEvery) {
            h.recordValue(values[i]);
        }
        return h;
    }

    /** Per-thread pattern: independent histograms merged by addition. */
    public static Histogram merge(Histogram a, Histogram b) {
        Histogram out = newHistogram();
        out.add(a);
        out.add(b);
        return out;
    }

    /** The lab's canonical percentile snapshot of a histogram. */
    public record Percentiles(
        long count, long p50, long p95, long p99, long p999, long max, int footprintBytes) {

        public static Percentiles of(Histogram h) {
            return new Percentiles(
                h.getTotalCount(),
                h.getValueAtPercentile(50.0),
                h.getValueAtPercentile(95.0),
                h.getValueAtPercentile(99.0),
                h.getValueAtPercentile(99.9),
                h.getMaxValue(),
                h.getEstimatedFootprintInBytes());
        }
    }
}
