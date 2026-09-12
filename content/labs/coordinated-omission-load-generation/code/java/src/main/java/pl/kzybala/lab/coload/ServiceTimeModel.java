package pl.kzybala.lab.coload;

/** Deterministic per-request service time on a virtual (logical) clock, measured in nanoseconds-equivalent units. */
public enum ServiceTimeModel {
    PERIODIC_STALL(100L, 50, 10_000_000L, -1, 0L),
    GC_PAUSE_INJECTION(100L, 0, 0L, 500, 50_000_000L),
    BOUNDED_OVERLOAD(150L, 0, 0L, -1, 0L);

    final long baseNanos;
    final int stallEveryN;
    final long stallNanos;
    final int pauseAtIndex;
    final long pauseNanos;

    ServiceTimeModel(long baseNanos, int stallEveryN, long stallNanos, int pauseAtIndex, long pauseNanos) {
        this.baseNanos = baseNanos;
        this.stallEveryN = stallEveryN;
        this.stallNanos = stallNanos;
        this.pauseAtIndex = pauseAtIndex;
        this.pauseNanos = pauseNanos;
    }

    public long serviceTimeNanos(int index) {
        if (stallEveryN > 0 && index > 0 && index % stallEveryN == 0) {
            return stallNanos;
        }
        if (pauseAtIndex >= 0 && index == pauseAtIndex) {
            return pauseNanos;
        }
        return baseNanos;
    }
}
