# Proposal: SIMD: Java Vector API and Rust

## Intent

Build a complete advanced Performance Lab that answers:

> When does explicit SIMD outperform scalar code, and when does memory bandwidth make it irrelevant?

## Hypothesis

Vectorization helps regular compute-heavy loops with sufficient data, but tails, alignment, masks and bandwidth can dominate.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Memory & Data track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: scalar baseline, auto-vectorized candidate, explicit SIMD, misaligned input, small-tail-heavy input.
- Exercise these datasets/profiles: sum/min/max, threshold filter, dot product, byte classification.
- Record at least these metrics: ns/element, cycles/element, vector instructions, bandwidth, speedup by size.
- Capture evidence using repository-supported subsets of: JMH, Criterion, perf stat, perfasm, cargo asm, compiler optimization reports.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-104
- PLAB-106

## Expected Learning Outcomes

- identify vectorizable kernels
- handle tails and features
- separate compute-bound from bandwidth-bound cases

## Known Benchmark Traps

- benchmarking unsupported CPU features
- forgetting scalar tail
- comparing different vector widths without disclosure
- assuming SIMD helps pointer-heavy code

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
