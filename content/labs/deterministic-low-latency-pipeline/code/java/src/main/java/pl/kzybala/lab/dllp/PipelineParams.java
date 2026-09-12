package pl.kzybala.lab.dllp;

/**
 * eventSizeBytes: fixed record size this dataset models (32/128/1024) — feeds decisionChecksum
 *   only as a seed component, since the correctness fixture never materializes real byte arrays.
 * producedPerTick: events arriving each tick (steady = constant, burst = burst-then-drain).
 * numShards: shard count for OPTIMIZED/OVERLOAD_PROFILE/FAULT_RESTART_PROFILE (NAIVE always uses 1).
 * capacityPerShard: bounded admission capacity per shard.
 * consumeRatePerShard: events each shard drains per tick.
 * restartAtTick / restartShard: FAULT_RESTART_PROFILE only — when/which shard is force-cleared.
 */
public record PipelineParams(
        int eventSizeBytes,
        KeyPattern keyPattern,
        int[] producedPerTick,
        int numShards,
        int capacityPerShard,
        int consumeRatePerShard,
        int restartAtTick,
        int restartShard) {}
