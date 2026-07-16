package pl.kzybala.lab.jitpipeline;

import java.util.Arrays;

/**
 * Deoptimization / uncommon-trap harness (kind=aux): phase 1 runs the
 * call site monomorphically until it is hot and compiled; phase 2 floods
 * the SAME call site with two new receiver types — invalidating the
 * speculative monomorphic compilation (uncommon trap → deoptimize →
 * reprofile → recompile polymorphic); phase 3 continues to steady state.
 * Every block's time is recorded so the deopt spike and recompilation are
 * visible AS EVENTS in the series, aligned with the compilation log
 * captured beside this JSON ("made not entrant" entries at review time).
 *
 * <p>Correctness: exact totals asserted in every phase.
 */
public final class DeoptTrajectoryHarness {

    public static void main(String[] args) {
        int blocksPerPhase = 6_000;
        int callsPerBlock = 64;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--blocks-per-phase" -> blocksPerPhase = Integer.parseInt(args[i + 1]);
                case "--calls-per-block" -> callsPerBlock = Integer.parseInt(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        WorkerPin pin = WorkerPin.pinningRequested() ? WorkerPin.establish("deopt", WorkerPin.CPU_A) : null;

        long[] amounts = PricingKernel.amounts();
        PricingKernel.Pricer basic = new PricingKernel.BasicPricer();
        PricingKernel.Pricer[] polluted = {
            new PricingKernel.BasicPricer(), new PricingKernel.DiscountPricer(), new PricingKernel.SurgePricer(),
        };
        int totalBlocks = blocksPerPhase * 3;
        long[] blockNanos = new long[totalBlocks];

        for (int block = 0; block < totalBlocks; block++) {
            boolean pollutedPhase = block >= blocksPerPhase && block < blocksPerPhase * 2;
            long start = System.nanoTime();
            long total = 0;
            for (int call = 0; call < callsPerBlock; call++) {
                if (pollutedPhase) {
                    total += PricingKernel.mixedTotal(polluted, amounts);
                } else {
                    total += PricingKernel.total(basic, amounts);
                }
            }
            blockNanos[block] = System.nanoTime() - start;
            long expected = (pollutedPhase ? 56392272L : 50215100L) * callsPerBlock;
            if (total != expected) {
                throw new IllegalStateException("kernel total wrong in block " + block + " — run is invalid");
            }
        }

        StringBuilder phases = new StringBuilder();
        String[] names = {"monomorphic-hot", "polluted-deopt", "post-recompile"};
        for (int phase = 0; phase < 3; phase++) {
            long[] slice = Arrays.copyOfRange(blockNanos, phase * blocksPerPhase, (phase + 1) * blocksPerPhase);
            long maxNs = Arrays.stream(slice).max().orElse(-1);
            Arrays.sort(slice);
            if (phase > 0) phases.append(", ");
            phases.append(String.format(
                "{\"phase\":\"%s\",\"p50Ns\":%d,\"p99Ns\":%d,\"maxNs\":%d}",
                names[phase], slice[slice.length / 2],
                slice[Math.min(slice.length - 1, (int) (slice.length * 0.99))], maxNs));
        }
        // the transition window verbatim — where the trap/deopt lands
        StringBuilder transition = new StringBuilder();
        int from = Math.max(0, blocksPerPhase - 32);
        for (int i = from; i < Math.min(totalBlocks, blocksPerPhase + 96); i++) {
            if (i > from) transition.append(',');
            transition.append(blockNanos[i]);
        }

        System.out.println("{");
        System.out.println("  \"harness\": \"DeoptTrajectoryHarness (deopt as an EVENT in a series — never an average)\",");
        System.out.println("  \"blocksPerPhase\": " + blocksPerPhase + ", \"callsPerBlock\": " + callsPerBlock + ",");
        System.out.println("  \"phases\": [" + phases + "],");
        System.out.println("  \"transitionWindowNsVerbatim\": [" + transition + "],");
        System.out.println("  \"pinned\": " + (pin != null) + ",");
        System.out.println("  \"note\": \"align the transition window with 'made not entrant'/recompilation entries in the jit-compilation log captured beside this file\"");
        System.out.println("}");
        if (pin != null) pin.verifyAndRecord();
    }
}
