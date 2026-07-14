# Proposal: Arena Lifetimes, Pools and Reuse

## Intent

Build a complete advanced Performance Lab that answers:

> Can explicit lifetime regions eliminate allocation pressure without becoming a manual-memory disaster?

## Hypothesis

Batch-scoped arenas and reusable buffers can make lifetime cost predictable, but pooling may retain memory, increase contention and complicate ownership.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Memory & Data track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: allocate per item, batch arena, thread-local reuse, global pool, bounded pool.
- Exercise these datasets/profiles: message batches, temporary parse trees, scratch buffers.
- Record at least these metrics: B/op, allocations/op, reset/close cost, RSS high-water mark, contention, p99.
- Capture evidence using repository-supported subsets of: JMH, JFR, async-profiler alloc/lock, Criterion, heap profiler.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-203
- PLAB-302

## Expected Learning Outcomes

- model lifetimes explicitly
- decide when pooling is harmful
- build bounded reuse strategies

## Known Benchmark Traps

- unbounded pools
- cross-thread ownership ambiguity
- pooling tiny objects already scalar-replaced
- excluding cleanup cost

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
