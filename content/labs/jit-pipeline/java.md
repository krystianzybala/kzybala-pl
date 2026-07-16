# JIT pipeline — Java

All experiments share one deterministic workload, `PricingKernel`: three
`Pricer` implementations with exact integer semantics over a fixed
xorshift64 input stream (the shared fixture pins every total — an
optimization that changes any answer invalidates the run by construction).

## Warm-up trajectory (dedicated harness — never JMH)

`WarmupTrajectoryHarness` starts cold and records every fixed-size block's
wall time from the very first invocation, preserving the series (first 512
blocks verbatim plus per-segment percentiles). The JVM writes its
compilation log (`-Xlog:jit+compilation`) beside the output so warm-up
steps can be aligned with C1/C2 compilation events at review time. On a
development machine the shape is already dramatic — a first block hundreds
of times slower than steady state — but only the native-Linux run is
publication evidence.

## Deoptimization / uncommon traps (dedicated harness)

`DeoptTrajectoryHarness` runs three phases over the same call site:
monomorphic-hot (compiled, speculative), polluted (two new receiver types
flood the site — guard fails, uncommon trap, deopt, reprofile, recompile),
and post-recompile steady state. The transition window is preserved
verbatim for alignment with "made not entrant" log entries.

## Steady state (JMH)

`JitSteadyStateBenchmark` measures the per-pass cost after warm-up for
call-site shapes `mono` / `bi` / `mega` (the inlining axis) and the
allocating `escape` kernel, which the runner executes twice — with default
flags and with `-XX:-DoEscapeAnalysis` — so the scalar-replacement effect
is isolated by exactly one flag, recorded in the run's evidence.

The executable benchmark and correctness suite are used internally to
produce the evidence shown by this laboratory.
