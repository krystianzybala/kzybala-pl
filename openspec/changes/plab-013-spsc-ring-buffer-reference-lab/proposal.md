# Proposal: SPSC Ring Buffer Reference Lab

## Intent

Build a complete reference Performance Lab that answers:

> How can one producer and one consumer exchange messages with bounded, allocation-free latency?

## Hypothesis

A power-of-two bounded ring with single-writer indices, correct acquire/release publication and cache-line separation can outperform general queues.

## Current Context

This change hardens and migrates an existing reference laboratory; it must preserve useful content and routes until parity is verified.

The lab is part of the Concurrency track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: blocking queue baseline, naive volatile ring, padded acquire/release ring, batched ring, busy-spin/yield/park wait strategies.
- Exercise these datasets/profiles: primitive messages, fixed binary frames, burst and steady traffic.
- Record at least these metrics: messages/s, one-way latency p50/p99/p999, CPU/core, allocation rate, failed polls.
- Capture evidence using repository-supported subsets of: JMH group/custom harness, Criterion/custom harness, HdrHistogram, perf, jcstress, loom/miri where relevant.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-402
- PLAB-102

## Expected Learning Outcomes

- implement correct SPSC publication
- understand wait strategy trade-offs
- measure bounded queue latency honestly

## Known Benchmark Traps

- using SPSC with multiple producers
- publishing before payload write
- coordinated omission
- ignoring shutdown and wraparound

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
