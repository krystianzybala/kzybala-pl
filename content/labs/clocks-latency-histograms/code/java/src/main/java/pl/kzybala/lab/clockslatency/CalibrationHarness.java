package pl.kzybala.lab.clockslatency;

import java.util.Arrays;

/**
 * Timer calibration harness (kind=aux in the runner): what does
 * {@code System.nanoTime()} actually cost and resolve on THIS host? The
 * three numbers a latency measurement must know about its own instrument:
 *
 * - per-call cost (amortized over a long back-to-back loop),
 * - observed granularity (smallest non-zero delta between consecutive
 *   calls — resolution as delivered, not as documented),
 * - monotonicity (nanoTime is specified monotonic within a JVM; violations
 *   would be an infrastructure red flag).
 *
 * Prints one JSON document; exits non-zero on a monotonicity violation.
 * Development runs are wiring checks; canonical values come from the
 * native-Linux host through the evidence runner.
 */
public final class CalibrationHarness {

    public static void main(String[] args) {
        int samples = 1_000_000;
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].equals("--samples")) {
                samples = Integer.parseInt(args[i + 1]);
            } else {
                throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        WorkerPin pin = WorkerPin.pinningRequested()
            ? WorkerPin.establish("calibrator", WorkerPin.CPU_A) : null;

        // warm the path so the measured loop runs compiled code
        long sink = 0;
        for (int i = 0; i < 200_000; i++) {
            sink += System.nanoTime();
        }

        // per-call cost: N calls back to back
        long start = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            sink += System.nanoTime();
        }
        long elapsed = System.nanoTime() - start;
        double nsPerCall = (double) elapsed / samples;

        // granularity + monotonicity over consecutive deltas
        long[] deltas = new long[100_000];
        long prev = System.nanoTime();
        long violations = 0;
        for (int i = 0; i < deltas.length; i++) {
            long now = System.nanoTime();
            deltas[i] = now - prev;
            if (now < prev) {
                violations++;
            }
            prev = now;
        }
        long minNonZero = Long.MAX_VALUE;
        long zeros = 0;
        for (long d : deltas) {
            if (d == 0) {
                zeros++;
            } else if (d > 0 && d < minNonZero) {
                minNonZero = d;
            }
        }
        long[] sorted = deltas.clone();
        Arrays.sort(sorted);
        long medianDelta = sorted[sorted.length / 2];

        if (pin != null) {
            pin.verifyAndRecord();
        }
        System.out.println("{");
        System.out.println("  \"harness\": \"CalibrationHarness (System.nanoTime cost/granularity/monotonicity)\",");
        System.out.println("  \"samples\": " + samples + ",");
        System.out.println("  \"nsPerCall\": " + String.format(java.util.Locale.ROOT, "%.3f", nsPerCall) + ",");
        System.out.println("  \"minNonZeroDeltaNs\": " + (minNonZero == Long.MAX_VALUE ? -1 : minNonZero) + ",");
        System.out.println("  \"medianDeltaNs\": " + medianDelta + ",");
        System.out.println("  \"zeroDeltas\": " + zeros + ",");
        System.out.println("  \"monotonicityViolations\": " + violations + ",");
        System.out.println("  \"sinkChecksum\": " + sink + ",");
        System.out.println("  \"note\": \"instrument calibration — subtract nsPerCall context from any per-op timestamped measurement; granularity bounds the smallest honestly reportable latency\"");
        System.out.println("}");
        if (violations > 0) {
            System.exit(1);
        }
    }
}
