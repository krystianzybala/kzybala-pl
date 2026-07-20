package pl.kzybala.lab.clockslatency;

/**
 * Deterministic single-server FIFO response-time model — the burst
 * dataset, and the lab's service-time vs response-time separation made
 * executable. Arrivals come in bursts of {@code burstSize} at
 * {@code burstIntervalNanos} spacing; service times come from the
 * deterministic bimodal stream. Response time = completion − arrival,
 * where completion = max(arrival, previous completion) + service.
 *
 * Pure integer arithmetic: Java and Rust produce identical response
 * sequences (fixture-checksummed).
 */
public final class ResponseTimeModel {

    private ResponseTimeModel() {}

    /** Response times for n operations under bursty arrivals. */
    public static long[] burstResponseTimes(
        long seed, int n, int burstSize, long burstIntervalNanos) {
        long[] service = SyntheticLatency.bimodalSequence(seed, n);
        long[] response = new long[n];
        long prevCompletion = 0;
        for (int i = 0; i < n; i++) {
            long arrival = (long) (i / burstSize) * burstIntervalNanos;
            long start = Math.max(arrival, prevCompletion);
            long completion = start + service[i];
            response[i] = completion - arrival;
            prevCompletion = completion;
        }
        return response;
    }
}
