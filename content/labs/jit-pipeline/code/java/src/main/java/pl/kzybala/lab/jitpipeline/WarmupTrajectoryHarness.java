package pl.kzybala.lab.jitpipeline;

import java.util.Arrays;

/**
 * Warm-up trajectory harness (kind=aux in the runner config) — startup,
 * interpreter phase, tiered compilation and steady state are a TIME
 * SERIES, deliberately never collapsed into one aggregate number and never
 * measured with JMH (whose entire design is to discard exactly this
 * trajectory). One pinned worker runs the monomorphic pricing kernel in
 * fixed blocks and records every block's wall time from the very first
 * invocation; the compilation log (-Xlog:jit+compilation, exact flags
 * recorded by the runner) lands next to this JSON so warm-up phases can
 * be aligned with compilation events at review time.
 *
 * <p>Correctness: every block's total is asserted against the shared
 * fixture — a wrong result at any tier invalidates the run.
 *
 * <p>Usage: {@code java ...WarmupTrajectoryHarness --blocks 20000
 * --calls-per-block 64} — deliberately run WITHOUT prior warm-up.
 */
public final class WarmupTrajectoryHarness {

    static final long EXPECTED_BASIC_TOTAL = 50215100L;

    public static void main(String[] args) {
        int blocks = 20_000;
        int callsPerBlock = 64;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--blocks" -> blocks = Integer.parseInt(args[i + 1]);
                case "--calls-per-block" -> callsPerBlock = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        WorkerPin pin = WorkerPin.pinningRequested() ? WorkerPin.establish("warmup", WorkerPin.CPU_A) : null;

        long[] amounts = PricingKernel.amounts();
        PricingKernel.Pricer pricer = new PricingKernel.BasicPricer();
        long[] blockNanos = new long[blocks]; // preallocated — the recording itself must not allocate
        long jvmStartNanos = System.nanoTime();

        for (int block = 0; block < blocks; block++) {
            long start = System.nanoTime();
            long total = 0;
            for (int call = 0; call < callsPerBlock; call++) {
                total += PricingKernel.total(pricer, amounts);
            }
            blockNanos[block] = System.nanoTime() - start;
            if (total != EXPECTED_BASIC_TOTAL * callsPerBlock) {
                throw new IllegalStateException("kernel produced a wrong total in block " + block + " — run is invalid");
            }
        }

        // Phase summaries: the full series is preserved (first 512 blocks
        // verbatim + per-segment percentiles); nothing here replaces it
        // with one number.
        long[] sortedTail = Arrays.copyOfRange(blockNanos, blocks - blocks / 10, blocks);
        Arrays.sort(sortedTail);
        StringBuilder firstBlocks = new StringBuilder();
        int verbatim = Math.min(512, blocks);
        for (int i = 0; i < verbatim; i++) {
            if (i > 0) firstBlocks.append(',');
            firstBlocks.append(blockNanos[i]);
        }
        StringBuilder segments = new StringBuilder();
        int segmentCount = 10;
        for (int segment = 0; segment < segmentCount; segment++) {
            int from = blocks / segmentCount * segment;
            int to = segment == segmentCount - 1 ? blocks : blocks / segmentCount * (segment + 1);
            long[] slice = Arrays.copyOfRange(blockNanos, from, to);
            Arrays.sort(slice);
            if (segment > 0) segments.append(", ");
            segments.append(String.format(
                "{\"segment\":%d,\"fromBlock\":%d,\"toBlock\":%d,\"p50Ns\":%d,\"p99Ns\":%d}",
                segment, from, to, slice[slice.length / 2], slice[Math.min(slice.length - 1, (int) (slice.length * 0.99))]));
        }

        System.out.println("{");
        System.out.println("  \"harness\": \"WarmupTrajectoryHarness (time series — never one aggregate number, never JMH)\",");
        System.out.println("  \"blocks\": " + blocks + ", \"callsPerBlock\": " + callsPerBlock + ",");
        System.out.println("  \"firstBlockNs\": " + blockNanos[0] + ",");
        System.out.println("  \"steadyStateTailP50Ns\": " + sortedTail[sortedTail.length / 2] + ",");
        System.out.println("  \"firstBlocksNsVerbatim\": [" + firstBlocks + "],");
        System.out.println("  \"segments\": [" + segments + "],");
        System.out.println("  \"harnessElapsedNs\": " + (System.nanoTime() - jvmStartNanos) + ",");
        System.out.println("  \"pinned\": " + (pin != null) + ",");
        System.out.println("  \"note\": \"align this trajectory with the jit-compilation log captured beside it; the JVM was started cold for this run — startup/interpreter/tiered phases are IN the series, not discarded\"");
        System.out.println("}");
        if (pin != null) pin.verifyAndRecord();
    }
}
