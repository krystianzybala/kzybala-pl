package pl.kzybala.lab.cpuaffinity;

import java.util.OptionalInt;

public enum PlacementTarget {
    UNPINNED_BASELINE,
    PINNED_ISOLATED_CORE,
    SMT_SIBLINGS,
    SAME_NUMA_NODE,
    REMOTE_NUMA_NODE;
    // IRQ_INTERFERENCE_PROFILE intentionally omitted from the runnable variant set: safely
    // reproducing IRQ affinity/interference requires host-level privileged configuration this
    // lab's default smoke/development experience must not require (design.md non-goal
    // "require privileged host tuning for the default smoke/development experience"); it is
    // documented as a theory-only, evidence-unavailable-by-default variant in theory.md.

    /** Resolves the CPU id to pin to for this target, or empty when the real topology can't supply one. */
    public OptionalInt resolveCpu(Topology topology) {
        if (!topology.supported()) {
            return OptionalInt.empty();
        }
        return switch (this) {
            case UNPINNED_BASELINE -> OptionalInt.empty(); // never pins, by definition
            case PINNED_ISOLATED_CORE -> topology.isolatedCpu();
            case SMT_SIBLINGS -> topology.smtSiblingCpu();
            case SAME_NUMA_NODE -> topology.sameNumaCpu();
            case REMOTE_NUMA_NODE -> topology.remoteNumaCpu();
        };
    }
}
