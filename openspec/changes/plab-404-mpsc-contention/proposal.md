# Proposal: MPSC Queues and Producer Contention

## Intent

Build a complete advanced Performance Lab that answers:

> What changes when many producers contend for one consumer-owned stream?

## Hypothesis

Shared producer sequencing introduces CAS and cache-line contention; batching, shards or per-producer queues can outperform a single MPSC structure.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Concurrency track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: single shared MPSC, batched claims, per-producer SPSC fan-in, mutex queue baseline, unbounded library queue comparison.
- Exercise these datasets/profiles: 2/4/8/physical-core producers, uniform and skewed producers, burst traffic.
- Record at least these metrics: aggregate throughput, producer p99 enqueue, consumer latency, CAS failures, cache transfers, fairness.
- Capture evidence using repository-supported subsets of: JMH custom harness, Criterion/custom harness, perf c2c, perf stat, async-profiler locks/cpu.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-403

## Expected Learning Outcomes

- identify MPSC contention points
- select fan-in architecture
- measure fairness and saturation

## Known Benchmark Traps

- comparing bounded and unbounded semantics
- letting consumer fall behind without reporting occupancy
- ignoring producer fairness
- using allocation-heavy payloads

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
