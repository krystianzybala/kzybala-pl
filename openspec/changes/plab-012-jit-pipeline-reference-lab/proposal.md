# Proposal: JIT Pipeline: Interpretation, C1, C2 and Deoptimization

## Intent

Build a complete reference Performance Lab that answers:

> How does Java code move from bytecode through tiered compilation, and why do benchmark results change during warm-up?

## Hypothesis

Tiered compilation improves steady-state throughput but introduces phase changes, recompilation and deoptimization that must be visible in any honest Java/Rust comparison.

## Current Context

This change hardens and migrates an existing reference laboratory; it must preserve useful content and routes until parity is verified.

The lab is part of the JVM Runtime track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: cold invocation, tiered warm-up, steady-state compiled, forced deoptimization, Rust debug vs release as anti-example.
- Exercise these datasets/profiles: small polymorphic call chain, numeric kernel, branch profile shift.
- Record at least these metrics: latency over time, compilation count, code size, deoptimization events, steady-state ns/op.
- Capture evidence using repository-supported subsets of: JMH, JFR, JITWatch, PrintCompilation/LogCompilation, perfasm, Criterion, cargo asm.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-101
- basic JVM knowledge

## Expected Learning Outcomes

- explain tiered compilation
- separate startup from steady state
- interpret compilation and deoptimization evidence

## Known Benchmark Traps

- timing Java before warm-up and Rust after optimization
- treating one warm-up threshold as universal
- forcing compilation flags without explaining them
- claiming JIT always wins or loses

## Dependencies

- `plab-001-performance-lab-foundation`
- `plab-011-unified-lab-framework`
- `plab-002-reproducible-benchmark-platform`
- `plab-003-results-provenance-publication`

## Completion Evidence

- Java and Rust correctness fixtures pass for all compared variants.
- Smoke and full benchmark profiles execute with captured metadata.
- Raw JMH/Criterion or macro-harness output is committed/imported through the result pipeline.
- Required profiler/counter evidence is linked from the lab page.
- Conclusions state mechanism, uncertainty, non-comparable aspects and host limitations.
- No result is labeled verified until it has been reproduced and reviewed.

## Native-Linux measurement policy

Publication measurements for this laboratory follow
`docs/measurement-environments.md`: development workstations produce
correctness/smoke signals only; canonical evidence comes from the dedicated
native-Linux benchmark host via the unified runner and batch workflow, and
the public page renders `awaiting-native-linux-measurement` until that
evidence is imported and reviewed.
