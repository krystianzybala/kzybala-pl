package pl.kzybala.lab.backpressure;

/**
 * capacity: bounded-variant queue capacity (ignored by UNBOUNDED).
 * producedPerTick: number of items produced at each tick index; total ticks == producedPerTick.length.
 * consumeRate: items the consumer removes per tick, applied after that tick's production.
 * keyCount: distinct keys for COALESCE_BY_KEY (item key = id % keyCount); ignored otherwise.
 * shedThresholdPercent: LOAD_SHEDDING sheds a newly produced item when queue occupancy already
 *   exceeds this percentage of capacity, even though the queue is not yet full.
 */
public record PipelineParams(int capacity, int[] producedPerTick, int consumeRate, int keyCount, int shedThresholdPercent) {}
