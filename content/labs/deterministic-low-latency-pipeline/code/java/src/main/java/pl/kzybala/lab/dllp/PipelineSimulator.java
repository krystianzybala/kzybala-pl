package pl.kzybala.lab.dllp;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A deterministic, single-threaded discrete-event simulation of the capstone pipeline: fixed
 * event schedule -> key-based single-writer sharding -> per-shard bounded FIFO admission
 * (identical mechanism to the Backpressure and Bounded Pipelines lab's BOUNDED_REJECT policy,
 * one instance per shard) -> zero-copy decode/decision. No real threads, no real byte buffers —
 * the real concurrent, real-buffer version lives in the benchmark harness (java.md/rust.md),
 * never in this correctness fixture.
 */
public final class PipelineSimulator {
    private PipelineSimulator() {}

    public static Outcome simulate(PipelineVariant variant, PipelineParams params) {
        int numShards = variant == PipelineVariant.NAIVE_OBJECT_QUEUE ? 1 : params.numShards();
        int capacity = variant == PipelineVariant.OVERLOAD_PROFILE
                ? Math.max(1, params.capacityPerShard() / 8)
                : (variant == PipelineVariant.NAIVE_OBJECT_QUEUE ? Integer.MAX_VALUE : params.capacityPerShard());

        @SuppressWarnings("unchecked")
        Deque<Integer>[] queues = new ArrayDeque[numShards];
        for (int s = 0; s < numShards; s++) {
            queues[s] = new ArrayDeque<>();
        }

        int produced = 0, delivered = 0, dropped = 0, restartLoss = 0, maxDepth = 0;
        long checksum = 0;
        int nextId = 0;
        int tick = 0;

        for (int producedThisTick : params.producedPerTick()) {
            for (int k = 0; k < producedThisTick; k++) {
                int id = nextId++;
                produced++;
                int key = params.keyPattern().keyFor(id);
                int shard = numShards > 1 ? Math.floorMod(key, numShards) : 0;
                if (queues[shard].size() < capacity) {
                    queues[shard].addLast(id);
                } else {
                    dropped++;
                }
            }

            if (variant == PipelineVariant.FAULT_RESTART_PROFILE && tick == params.restartAtTick()) {
                int victim = Math.floorMod(params.restartShard(), numShards);
                restartLoss += queues[victim].size();
                queues[victim].clear();
            }

            for (int s = 0; s < numShards; s++) {
                maxDepth = Math.max(maxDepth, queues[s].size());
                for (int c = 0; c < params.consumeRatePerShard(); c++) {
                    Integer id = queues[s].pollFirst();
                    if (id == null) break;
                    int key = params.keyPattern().keyFor(id);
                    checksum ^= Decision.decisionChecksum(key, id, params.eventSizeBytes());
                    delivered++;
                }
            }
            tick++;
        }

        // Trailing synchronous drain so the invariant holds with zero items left queued.
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (int s = 0; s < numShards; s++) {
                Integer id = queues[s].pollFirst();
                if (id != null) {
                    int key = params.keyPattern().keyFor(id);
                    checksum ^= Decision.decisionChecksum(key, id, params.eventSizeBytes());
                    delivered++;
                    progressed = true;
                }
            }
        }

        return new Outcome(produced, delivered, dropped, restartLoss, checksum, maxDepth);
    }
}
