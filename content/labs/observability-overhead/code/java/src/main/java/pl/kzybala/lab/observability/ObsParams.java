package pl.kzybala.lab.observability;

/**
 * ids: the sequence of hot-path calls to make.
 * distinctKeyCount: cardinality of the label key space for METRICS_LABELS (key = id % distinctKeyCount).
 * sampleRate: SAMPLED_TRACING captures a real stack trace once every sampleRate calls (id % sampleRate == 0).
 * asyncQueueCapacity: bounded capacity for ASYNC_BOUNDED_LOGGING.
 * stackDepth: recursion depth before reaching the hot path, for the "stack trace path" dataset.
 */
public record ObsParams(int[] ids, int distinctKeyCount, int sampleRate, int asyncQueueCapacity, int stackDepth) {}
