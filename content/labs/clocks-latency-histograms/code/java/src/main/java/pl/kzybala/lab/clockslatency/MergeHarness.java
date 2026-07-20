package pl.kzybala.lab.clockslatency;

import org.HdrHistogram.Histogram;

/**
 * Per-thread recording + merge harness (kind=aux, two pinned workers):
 * each worker records its own deterministic stream into its OWN histogram
 * (no sharing, no synchronization on the hot path), then the merged
 * histogram must match the shared fixture exactly. Per-worker recording
 * wall time is the host measurement; the merged distribution is the
 * deterministic correctness check.
 *
 * Exits non-zero on fixture mismatch or worker failure.
 */
public final class MergeHarness {

    public static void main(String[] args) throws InterruptedException {
        final int n = 50_000;
        final Histogram[] histograms = new Histogram[2];
        final long[] recordNanos = new long[2];
        final long[] seeds = {42, 43};

        Thread[] workers = new Thread[2];
        for (int w = 0; w < 2; w++) {
            final int idx = w;
            workers[w] = new Thread(() -> {
                WorkerPin pin = WorkerPin.pinningRequested()
                    ? WorkerPin.establish("recorder-" + idx,
                        idx == 0 ? WorkerPin.CPU_A : WorkerPin.CPU_B)
                    : null;
                long[] values = SyntheticLatency.bimodalSequence(seeds[idx], n);
                Histogram h = LatencyHistograms.newHistogram();
                long start = System.nanoTime();
                for (long v : values) {
                    h.recordValue(v);
                }
                recordNanos[idx] = System.nanoTime() - start;
                histograms[idx] = h;
                if (pin != null) {
                    pin.verifyAndRecord();
                }
            }, "recorder-" + w);
            workers[w].start();
        }
        for (Thread t : workers) {
            t.join(60_000);
        }
        if (histograms[0] == null || histograms[1] == null) {
            System.err.println("merge-harness: a recorder did not complete");
            System.exit(1);
        }

        var merged = LatencyHistograms.Percentiles.of(
            LatencyHistograms.merge(histograms[0], histograms[1]));
        boolean fixturesOk = merged.count() == 100_000 && merged.p50() == 1009
            && merged.p99() == 55903 && merged.p999() == 59711 && merged.max() == 59999;

        System.out.println("{");
        System.out.println("  \"harness\": \"MergeHarness (per-thread recording, exact merge)\",");
        System.out.println("  \"perWorkerValues\": " + n + ",");
        System.out.println("  \"recorder0WallNanos\": " + recordNanos[0] + ",");
        System.out.println("  \"recorder1WallNanos\": " + recordNanos[1] + ",");
        System.out.println("  \"merged\": {\"count\":" + merged.count() + ",\"p50\":" + merged.p50()
            + ",\"p95\":" + merged.p95() + ",\"p99\":" + merged.p99()
            + ",\"p999\":" + merged.p999() + ",\"max\":" + merged.max() + "},");
        System.out.println("  \"fixtureExact\": " + fixturesOk + ",");
        System.out.println("  \"note\": \"per-thread histograms cost zero hot-path coordination; the merge is exact by construction (bucket-count addition)\"");
        System.out.println("}");
        if (!fixturesOk) {
            System.exit(1);
        }
    }
}
