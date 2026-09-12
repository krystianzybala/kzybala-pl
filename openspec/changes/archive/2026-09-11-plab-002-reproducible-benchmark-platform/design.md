# Design: Reproducible Benchmark Platform

## Context

This is a brownfield change. The current kzybala.pl Performance Lab, its routes, styling, Java/Rust repositories, benchmark assets and deployment mechanics must be inspected before implementation. The specification defines behavior and quality; it does not justify replacing working infrastructure for fashion.

## Goals

- Define pinned Java, Rust, JMH and Criterion toolchain resolution.
- Capture host CPU, topology, OS, kernel, governor, frequency, memory and runtime flags.
- Create canonical benchmark commands and profiles for micro, concurrent and macro labs.
- Add correctness checks before timing and smoke/full/release run modes.
- Detect uncontrolled or incomparable environments and label results accordingly.

## Explicit Constraints

- Repository reality beats guessed filenames, frameworks and module names.
- Java and Rust examples must implement equivalent semantics before their numbers are compared.
- Raw benchmark output and environment metadata are primary evidence.
- Mean latency alone is not sufficient for latency-sensitive conclusions.
- Client-side browser benchmarks are educational demonstrations only unless the environment is controlled and clearly labeled.
- Existing False Sharing, Ring Buffer and JIT Pipeline work must be treated as migration inputs.

## Profiles

Smoke proves wiring, development supports iteration, full gathers statistically useful evidence, and publication adds host stabilization plus profiler captures.

## Environment capture

Use non-privileged probes by default. Optional affinity, governor or NUMA tuning must be capability-detected and recorded, never assumed.

## Correctness fixtures

Each lab defines canonical input/output fixtures shared by Java and Rust. Benchmarks may add generated datasets only after fixture equivalence passes.

## Result identity

Hash the lab id, implementation revision, toolchain, parameters and environment metadata to derive a stable run id.

## CI policy

CI runs smoke and correctness profiles. Publication-grade measurements run on a designated controlled host and are never synthesized by ordinary shared runners.

## Validation Strategy

1. Validate content/schema invariants at build time.
2. Run unit and integration tests for the changed platform behavior.
3. Run smoke benchmarks only to prove wiring.
4. Preserve publication-grade benchmark execution for controlled hosts.
5. Review all claims against raw evidence and comparability rules.

## Rollback

Keep migration changes additive until the new capability has parity. Existing routes and assets remain available until tests and manual review confirm replacement behavior.
