package pl.kzybala.lab.tpc;

/** Common result shape: the final per-key counters (used by the
 * correctness oracle to check exact expected counts) plus, for the
 * rebalance variant only, the measured drain/handoff pause. */
public final class TpcResult {
    public final long[] finalCounters; // indexed by key
    public final long rebalanceCostNanos; // 0 for non-rebalance variants

    public TpcResult(long[] finalCounters, long rebalanceCostNanos) {
        this.finalCounters = finalCounters;
        this.rebalanceCostNanos = rebalanceCostNanos;
    }

    public boolean isCorrectUniform() {
        for (int k = 0; k < finalCounters.length; k++) {
            if (finalCounters[k] != TpcFixtures.expectedUniform(k)) return false;
        }
        return true;
    }

    public boolean isCorrectSkewed() {
        for (int k = 0; k < finalCounters.length; k++) {
            if (finalCounters[k] != TpcFixtures.expectedSkewed(k)) return false;
        }
        return true;
    }
}
