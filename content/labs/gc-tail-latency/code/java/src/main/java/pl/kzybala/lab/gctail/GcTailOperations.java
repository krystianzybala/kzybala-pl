package pl.kzybala.lab.gctail;

import java.util.ArrayList;
import java.util.List;

/**
 * The five allocation-pattern variants. Every variant reads the
 * identical value stream and accumulates the identical checksum
 * (../fixtures/gc-tail-latency-fixtures.json) — allocation pattern and
 * collector choice change GC pressure and pause behavior, never the
 * result.
 */
public final class GcTailOperations {

    private static final int BURST_QUIET_LEN = 100;
    private static final int BURST_LEN = 20;

    private GcTailOperations() {}

    /** Low allocation/reuse: one Node, mutated in place every iteration. */
    public static long sumLowAllocationReuse(long[] values, int extraSize) {
        Node reused = new Node(0, extraSize);
        long sum = 0;
        for (long v : values) {
            reused.value = v;
            reused.extra[0] = v;
            sum += reused.value;
        }
        return sum;
    }

    /** Steady high allocation: a fresh Node every iteration, discarded immediately. */
    public static long sumSteadyHighAllocation(long[] values, int extraSize) {
        long sum = 0;
        for (long v : values) {
            Node n = new Node(v, extraSize);
            sum += n.value;
        }
        return sum;
    }

    /** Bursty allocation: alternating quiet (reused) and burst (larger, fresh) blocks. */
    public static long sumBurstyAllocation(long[] values, int extraSize) {
        Node reused = new Node(0, extraSize);
        long sum = 0;
        int i = 0;
        int n = values.length;
        while (i < n) {
            int quietEnd = Math.min(i + BURST_QUIET_LEN, n);
            for (; i < quietEnd; i++) {
                reused.value = values[i];
                sum += reused.value;
            }
            int burstEnd = Math.min(i + BURST_LEN, n);
            for (; i < burstEnd; i++) {
                Node fresh = new Node(values[i], extraSize * 8);
                sum += fresh.value;
            }
        }
        return sum;
    }

    /** Growing live set: a fresh Node every iteration, retained forever — live set grows monotonically. */
    public static long sumGrowingLiveSet(long[] values, int extraSize) {
        List<Node> retained = new ArrayList<>(values.length);
        long sum = 0;
        for (long v : values) {
            Node n = new Node(v, extraSize);
            retained.add(n);
            sum += n.value;
        }
        long check = 0;
        for (Node n : retained) check += n.value;
        if (check != sum) {
            throw new IllegalStateException("retained live set does not match the running sum");
        }
        return sum;
    }

    /** Collector matrix: identical code to steadyHighAllocation — only the launching JVM's collector flag differs. */
    public static long sumCollectorMatrix(long[] values, int extraSize) {
        return sumSteadyHighAllocation(values, extraSize);
    }

    public static long run(String variant, long[] values, int extraSize) {
        return switch (variant) {
            case "lowAllocationReuse" -> sumLowAllocationReuse(values, extraSize);
            case "steadyHighAllocation" -> sumSteadyHighAllocation(values, extraSize);
            case "burstyAllocation" -> sumBurstyAllocation(values, extraSize);
            case "growingLiveSet" -> sumGrowingLiveSet(values, extraSize);
            case "collectorMatrix" -> sumCollectorMatrix(values, extraSize);
            default -> throw new IllegalArgumentException("unknown variant: " + variant);
        };
    }
}
