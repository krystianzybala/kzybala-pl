# Proposal: Branch Prediction and Data Distribution

## Intent

Build a complete intermediate Performance Lab that answers:

> When does a simple conditional become more expensive than the work guarded by it?

## Hypothesis

Unpredictable branches produce measurable front-end stalls, while sorted or biased data allows hardware predictors and JIT/compiler transformations to recover throughput.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Measurement & CPU track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: 90/10 biased branch, 50/50 random branch, sorted input, branchless mask/select.
- Exercise these datasets/profiles: byte flags, integer thresholds, mixed hot/cold records.
- Record at least these metrics: ns/element, branches, branch-misses, IPC, cycles/element.
- Capture evidence using repository-supported subsets of: JMH, Criterion, perf stat, perf record, perfasm, cargo asm.
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
- basic CPU pipeline concepts

## Expected Learning Outcomes

- connect data distribution to branch misses
- distinguish branchless wins from extra-work losses
- read relevant perf counters

## Known Benchmark Traps

- regenerating random data inside measurement
- compiler removing the branch
- confusing vectorization with branch prediction
- using one data distribution

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
