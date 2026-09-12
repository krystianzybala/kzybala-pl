package pl.kzybala.lab.backpressure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A deterministic, single-threaded tick simulation of a bounded producer/consumer pipeline
 * under each overload policy. Determinism (fixed production schedule, fixed consume rate, no
 * wall-clock timing) is what makes this a correctness fixture rather than a flaky concurrency
 * test — the real concurrent timing/latency questions this lab teaches are answered separately
 * by the JMH/Criterion benchmark harness, which measures wall-clock cost of the same policies
 * under real threads (see benchmark.md).
 */
public final class PipelineSimulator {
    private PipelineSimulator() {}

    public static SimResult simulate(Policy policy, PipelineParams params) {
        Deque<Integer> queue = new ArrayDeque<>();
        Deque<Integer> blockBacklog = new ArrayDeque<>(); // BOUNDED_BLOCK only
        Map<Integer, Integer> coalesceMap = new LinkedHashMap<>(); // COALESCE_BY_KEY only

        int produced = 0, delivered = 0, rejected = 0, dropped = 0, superseded = 0, maxDepth = 0;
        int nextId = 0;

        for (int producedThisTick : params.producedPerTick()) {
            // BOUNDED_BLOCK: retry backlog before admitting anything new this tick.
            if (policy == Policy.BOUNDED_BLOCK) {
                while (!blockBacklog.isEmpty() && queue.size() < params.capacity()) {
                    queue.addLast(blockBacklog.pollFirst());
                }
            }

            for (int i = 0; i < producedThisTick; i++) {
                int id = nextId++;
                produced++;
                int key = params.keyCount() > 0 ? id % params.keyCount() : id;

                switch (policy) {
                    case UNBOUNDED -> queue.addLast(id);
                    case BOUNDED_REJECT -> {
                        if (queue.size() < params.capacity()) {
                            queue.addLast(id);
                        } else {
                            rejected++;
                        }
                    }
                    case BOUNDED_BLOCK -> {
                        if (queue.size() < params.capacity()) {
                            queue.addLast(id);
                        } else {
                            blockBacklog.addLast(id);
                        }
                    }
                    case DROP_OLDEST -> {
                        if (queue.size() >= params.capacity() && !queue.isEmpty()) {
                            queue.pollFirst();
                            dropped++;
                        }
                        queue.addLast(id);
                    }
                    case COALESCE_BY_KEY -> {
                        Integer previous = coalesceMap.put(key, id);
                        if (previous != null) {
                            superseded++;
                        }
                    }
                    case LOAD_SHEDDING -> {
                        long occupancyPercent = params.capacity() == 0 ? 0
                                : (100L * queue.size()) / params.capacity();
                        if (queue.size() >= params.capacity()) {
                            dropped++;
                        } else if (occupancyPercent >= params.shedThresholdPercent() && (id % 2 == 0)) {
                            // Deterministic shedding rule: once above threshold occupancy, shed every
                            // other arriving item rather than admitting all of them — proactive load
                            // shedding, not just a hard-full rejection.
                            dropped++;
                        } else {
                            queue.addLast(id);
                        }
                    }
                }
            }

            int currentDepth = policy == Policy.COALESCE_BY_KEY ? coalesceMap.size() : queue.size();
            maxDepth = Math.max(maxDepth, currentDepth);

            // Consumer drains up to consumeRate items this tick.
            for (int c = 0; c < params.consumeRate(); c++) {
                if (policy == Policy.COALESCE_BY_KEY) {
                    if (coalesceMap.isEmpty()) break;
                    Iterator<Integer> it = coalesceMap.keySet().iterator();
                    int key = it.next();
                    it.remove();
                    delivered++;
                } else {
                    Integer id = queue.pollFirst();
                    if (id == null) break;
                    delivered++;
                }
            }
        }

        // Drain any remainder synchronously so the correctness invariant (produced ==
        // delivered + rejected + dropped + superseded) holds with no "still queued" remainder;
        // real datasets always include a trailing all-drain tail long enough to make this a no-op.
        while (true) {
            if (policy == Policy.COALESCE_BY_KEY) {
                if (coalesceMap.isEmpty()) break;
                Iterator<Integer> it = coalesceMap.keySet().iterator();
                it.next();
                it.remove();
                delivered++;
            } else {
                if (!blockBacklog.isEmpty() && queue.size() < params.capacity()) {
                    queue.addLast(blockBacklog.pollFirst());
                    continue;
                }
                Integer id = queue.pollFirst();
                if (id == null) break;
                delivered++;
            }
        }

        return new SimResult(produced, delivered, rejected, dropped, superseded, maxDepth);
    }
}
