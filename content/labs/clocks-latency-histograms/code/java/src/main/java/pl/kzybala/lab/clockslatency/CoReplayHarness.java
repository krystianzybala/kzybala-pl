package pl.kzybala.lab.clockslatency;

import org.HdrHistogram.Histogram;

/**
 * Coordinated-omission replay harness (kind=aux in the runner): replays
 * the DETERMINISTIC pause-injection or burst dataset through naive and
 * corrected/response recording and prints both percentile sets — the
 * distribution content is fixture-exact (verified against the shared
 * fixtures before printing), while the recording wall-time is a real
 * measurement of this host's recording throughput.
 *
 * Exits non-zero if the replayed distributions deviate from the shared
 * fixtures — a deviation means the instrument itself is broken.
 */
public final class CoReplayHarness {

    public static void main(String[] args) {
        String dataset = "pause";
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--dataset")) {
                dataset = args[i + 1];
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        WorkerPin pin = WorkerPin.pinningRequested()
            ? WorkerPin.establish("replayer", WorkerPin.CPU_A) : null;

        long[] first;
        long[] second;
        LatencyHistograms.Percentiles firstP;
        LatencyHistograms.Percentiles secondP;
        String firstName;
        String secondName;
        long recordStart;
        long recordElapsed;
        boolean fixturesOk;

        if (dataset.equals("pause")) {
            first = SyntheticLatency.pauseInjectedSequence(42, 100_000);
            second = first;
            firstName = "naive";
            secondName = "corrected";
            recordStart = System.nanoTime();
            Histogram naive = LatencyHistograms.recordAll(first);
            Histogram corrected = LatencyHistograms.recordCorrected(second, 1000);
            recordElapsed = System.nanoTime() - recordStart;
            firstP = LatencyHistograms.Percentiles.of(naive);
            secondP = LatencyHistograms.Percentiles.of(corrected);
            fixturesOk = firstP.p999() == 59999 && secondP.p999() == 4997119
                && secondP.count() == 837236;
        } else if (dataset.equals("burst")) {
            first = SyntheticLatency.bimodalSequence(42, 100_000);
            second = ResponseTimeModel.burstResponseTimes(42, 100_000, 10, 50_000);
            firstName = "serviceTime";
            secondName = "responseTime";
            recordStart = System.nanoTime();
            Histogram service = LatencyHistograms.recordAll(first);
            Histogram response = LatencyHistograms.recordAll(second);
            recordElapsed = System.nanoTime() - recordStart;
            firstP = LatencyHistograms.Percentiles.of(service);
            secondP = LatencyHistograms.Percentiles.of(response);
            fixturesOk = firstP.p50() == 1009 && secondP.p50() == 21023
                && secondP.p999() == 316159;
        } else {
            throw new IllegalArgumentException("unknown --dataset " + dataset + " (pause|burst)");
        }

        if (pin != null) {
            pin.verifyAndRecord();
        }
        System.out.println("{");
        System.out.println("  \"harness\": \"CoReplayHarness (deterministic distribution replay)\",");
        System.out.println("  \"dataset\": \"" + dataset + "\",");
        System.out.println("  \"" + firstName + "\": " + json(firstP) + ",");
        System.out.println("  \"" + secondName + "\": " + json(secondP) + ",");
        System.out.println("  \"recordingWallNanosFor200kValues\": " + recordElapsed + ",");
        System.out.println("  \"fixtureExact\": " + fixturesOk + ",");
        System.out.println("  \"note\": \"distribution content is deterministic (shared fixtures); only recordingWallNanos is a host measurement\"");
        System.out.println("}");
        if (!fixturesOk) {
            System.exit(1);
        }
    }

    private static String json(LatencyHistograms.Percentiles p) {
        return "{\"count\":" + p.count() + ",\"p50\":" + p.p50() + ",\"p95\":" + p.p95()
            + ",\"p99\":" + p.p99() + ",\"p999\":" + p.p999() + ",\"max\":" + p.max()
            + ",\"footprintBytes\":" + p.footprintBytes() + "}";
    }
}
