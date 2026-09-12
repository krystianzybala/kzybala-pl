package pl.kzybala.lab.coload;

/**
 * count: number of requests to generate.
 * intervalNanos: the target/intended inter-arrival interval for every open-loop variant.
 * queueCapacity: max requests admitted-but-not-yet-completed at once; an open-loop send that
 *   would exceed this is a "missed schedule" (dropped, never admitted) — the honest alternative
 *   to silently queuing forever.
 * burstSize: BURST_SCHEDULE groups this many consecutive requests at the same intended send time.
 */
public record LoadParams(int count, ServiceTimeModel serviceModel, long intervalNanos, int queueCapacity, int burstSize) {}
