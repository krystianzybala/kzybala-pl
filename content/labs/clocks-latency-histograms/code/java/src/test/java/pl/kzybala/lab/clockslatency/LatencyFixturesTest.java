package pl.kzybala.lab.clockslatency;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared-fixture correctness gate
 * (code/fixtures/clocks-latency-histograms-fixtures.json): sequences,
 * recording modes, coordinated-omission correction and merging must all
 * reproduce these exact values — the Rust suite pins the identical
 * numbers. Histogram footprint is implementation-specific and asserted
 * only as positive, never as a shared value.
 */
class LatencyFixturesTest {

    static final long BIMODAL_CHECKSUM = 3641811140783620904L;
    static final long PAUSED_CHECKSUM = -3209529033418889176L;

    @Test
    void bimodalSequenceMatchesTheSharedFixture() {
        long[] seq = SyntheticLatency.bimodalSequence(42, 100_000);
        assertEquals(BIMODAL_CHECKSUM, SyntheticLatency.checksum(seq));
        assertArrayEquals(new long[] {874, 1071, 954, 936, 962},
            java.util.Arrays.copyOf(seq, 5));
    }

    @Test
    void pauseInjectedSequenceMatchesTheSharedFixture() {
        long[] seq = SyntheticLatency.pauseInjectedSequence(42, 100_000);
        assertEquals(PAUSED_CHECKSUM, SyntheticLatency.checksum(seq));
        // every 1000th op carries the 5 ms stall, nothing else does
        assertTrue(seq[999] > 5_000_000L && seq[998] < 100_000L);
    }

    @Test
    void fullRecordingMatchesThePercentileFixture() {
        var p = LatencyHistograms.Percentiles.of(
            LatencyHistograms.recordAll(SyntheticLatency.bimodalSequence(42, 100_000)));
        assertEquals(100_000, p.count());
        assertEquals(1009, p.p50());
        assertEquals(1194, p.p95());
        assertEquals(55903, p.p99());
        assertEquals(59711, p.p999());
        assertEquals(59999, p.max());
        assertTrue(p.footprintBytes() > 0);
    }

    @Test
    void sampledRecordingShiftsTailPercentilesButNotTheMedian() {
        var p = LatencyHistograms.Percentiles.of(
            LatencyHistograms.recordSampled(SyntheticLatency.bimodalSequence(42, 100_000), 64));
        assertEquals(1563, p.count());
        assertEquals(1008, p.p50());
        assertEquals(1193, p.p95());
        assertEquals(56799, p.p99());
        assertEquals(59807, p.p999());
        assertEquals(59999, p.max());
    }

    @Test
    void coordinatedOmissionCorrectionMatchesTheFixtureAndMovesTheTailByOrdersOfMagnitude() {
        long[] paused = SyntheticLatency.pauseInjectedSequence(42, 100_000);
        var naive = LatencyHistograms.Percentiles.of(LatencyHistograms.recordAll(paused));
        assertEquals(100_000, naive.count());
        assertEquals(1009, naive.p50());
        assertEquals(56223, naive.p99());
        // p999 sits exactly on the stall cliff; the Java implementation
        // resolves the tie below it (59999), the Rust port on it (5001215)
        // — a documented instrument difference (fixtures.json), excluded
        // from cross-language pinning.
        assertEquals(59999, naive.p999());
        assertEquals(5062655, naive.max());

        var corrected = LatencyHistograms.Percentiles.of(
            LatencyHistograms.recordCorrected(paused, 1000));
        assertEquals(837236, corrected.count(), "backfilled arrivals must be recorded");
        assertEquals(819199, corrected.p50());
        assertEquals(4587519, corrected.p95());
        assertEquals(4923391, corrected.p99());
        assertEquals(4997119, corrected.p999());
        assertEquals(5062655, corrected.max());
        // the teaching point, asserted on p99 (off-boundary, identical in
        // both implementations): naive recording hides the stall's effect
        // by ~two orders of magnitude
        assertTrue(corrected.p99() > naive.p99() * 50);
    }

    @Test
    void perThreadHistogramsMergeExactly() {
        Histogram a = LatencyHistograms.recordAll(SyntheticLatency.bimodalSequence(42, 50_000));
        Histogram b = LatencyHistograms.recordAll(SyntheticLatency.bimodalSequence(43, 50_000));
        var merged = LatencyHistograms.Percentiles.of(LatencyHistograms.merge(a, b));
        assertEquals(100_000, merged.count());
        assertEquals(1009, merged.p50());
        assertEquals(1194, merged.p95());
        assertEquals(55903, merged.p99());
        assertEquals(59711, merged.p999());
        assertEquals(59999, merged.max());
    }

    @Test
    void burstResponseTimesSeparateServiceTimeFromResponseTime() {
        long[] response = ResponseTimeModel.burstResponseTimes(42, 100_000, 10, 50_000);
        assertEquals(8047843593492953431L, SyntheticLatency.checksum(response));
        var resp = LatencyHistograms.Percentiles.of(LatencyHistograms.recordAll(response));
        assertEquals(100_000, resp.count());
        assertEquals(21023, resp.p50());
        assertEquals(140927, resp.p95());
        assertEquals(223615, resp.p99());
        assertEquals(316159, resp.p999());
        assertEquals(377599, resp.max());
        // the same operations' SERVICE times: an entirely different answer —
        // conflating the two is the trap this dataset exists to expose
        var svc = LatencyHistograms.Percentiles.of(
            LatencyHistograms.recordAll(SyntheticLatency.bimodalSequence(42, 100_000)));
        assertEquals(1009, svc.p50());
        assertTrue(resp.p50() > svc.p50() * 10,
            "response p50 must dwarf service p50 under bursty arrivals");
    }

    @Test
    void recordingIsAllocationFreeAfterConstruction() {
        // structural guarantee the benchmark relies on: recording the whole
        // 100k sequence into a preallocated histogram touches no new
        // buckets beyond the preallocated dynamic range (auto-resize off)
        Histogram h = LatencyHistograms.newHistogram();
        assertTrue(!h.isAutoResize(), "fixed dynamic range — no resize allocation on the hot path");
        for (long v : SyntheticLatency.bimodalSequence(42, 100_000)) {
            h.recordValue(v);
        }
        assertEquals(100_000, h.getTotalCount());
    }
}
