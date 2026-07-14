# Proposal: Benchmark Harness Traps

## Intent

Build a complete foundational Performance Lab that answers:

> How can a benchmark report convincing numbers while measuring almost nothing useful?

## Hypothesis

Dead-code elimination, constant folding, state leakage, insufficient warm-up and incorrect parameterization can dominate the result more than the code under test.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Measurement & CPU track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: constant-foldable input vs runtime input, returned result vs consumed result, setup inside timed region vs outside, single fork/process vs isolated forks/processes.
- Exercise these datasets/profiles: tiny scalar operation, array reduction, parser with reusable input, stateful counter.
- Record at least these metrics: ns/op, ops/s, confidence interval, variance, allocation/op.
- Capture evidence using repository-supported subsets of: JMH, Criterion, perf stat, perfasm or JIT assembly, cargo asm or objdump.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- basic Java and Rust
- ability to run JMH and cargo bench

## Expected Learning Outcomes

- recognize invalid microbenchmarks
- construct equivalent harnesses
- explain why raw nanoseconds alone are insufficient

## Known Benchmark Traps

- benchmarking debug builds
- sharing mutable state between iterations
- comparing default JMH and Criterion settings as if identical
- publishing only the best run

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
