# Proposal: False Sharing Reference Lab

## Intent

Build a complete reference Performance Lab that answers:

> Why can independent counters destroy throughput when they occupy the same cache line?

## Hypothesis

Coherence traffic, not logical sharing, causes the collapse; padding or ownership partitioning restores scalability.

## Current Context

This change hardens and migrates an existing reference laboratory; it must preserve useful content and routes until parity is verified.

The lab is part of the Concurrency track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: adjacent counters, manually padded counters, runtime-assisted padding, per-thread shard plus reduction.
- Exercise these datasets/profiles: two threads, physical-core sweep, SMT sibling placement, cross-socket placement where available.
- Record at least these metrics: ops/s, ns/update, cache-to-cache transfers, LLC misses, CPU utilization.
- Capture evidence using repository-supported subsets of: JMH group benchmarks, Criterion/custom threaded harness, perf c2c, perf stat, async-profiler.
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
- PLAB-104

## Expected Learning Outcomes

- explain false sharing at coherence level
- validate padding physically
- prefer ownership partitioning where possible

## Known Benchmark Traps

- using thread count without topology
- padding local objects that are not adjacent
- claiming volatile itself is the cause
- using different memory-order guarantees

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
