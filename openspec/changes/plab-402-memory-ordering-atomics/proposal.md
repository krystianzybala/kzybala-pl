# Proposal: Memory Ordering: VarHandles and Rust Atomics

## Intent

Build a complete advanced Performance Lab that answers:

> What correctness and performance guarantees do plain, opaque, acquire/release and sequentially consistent operations provide?

## Hypothesis

Weaker valid orderings can reduce fences and contention, but incorrect publication creates rare failures that benchmarks alone may not reveal.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Concurrency track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: plain broken publication demonstration, acquire/release publication, volatile/seq-cst publication, CAS loop, fence-based variant.
- Exercise these datasets/profiles: single-slot mailbox, sequence flag plus payload, counter update.
- Record at least these metrics: ops/s, failed CAS, instructions/fences, correctness outcomes, p99.
- Capture evidence using repository-supported subsets of: JMH, jcstress, Criterion, loom, perfasm, cargo asm, perf stat.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-401
- memory-model basics

## Expected Learning Outcomes

- choose the weakest correct ordering
- prove publication behavior
- distinguish language memory models from hardware

## Known Benchmark Traps

- using performance tests as correctness proof
- mapping Java and Rust orderings mechanically
- benchmarking an intentionally racy variant without isolation
- omitting forbidden outcomes

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
