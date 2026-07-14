# Proposal: Allocation Cost and Object Layout

## Intent

Build a complete intermediate Performance Lab that answers:

> What are the real byte and CPU costs of convenient object models?

## Hypothesis

Headers, alignment, references and allocator behavior can make logical data several times larger than packed representations.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Memory & Data track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: boxed object graph, flat primitive arrays, packed off-heap struct, reused mutable holder.
- Exercise these datasets/profiles: orders/quotes, tree nodes, small tuples.
- Record at least these metrics: bytes/logical-record, B/op, allocation rate, cache misses, construction ns/record.
- Capture evidence using repository-supported subsets of: JOL, JMH, JFR, async-profiler alloc, Criterion, size_of, heap profiler.
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

## Expected Learning Outcomes

- calculate physical footprint
- connect footprint to locality
- choose representations deliberately

## Known Benchmark Traps

- ignoring alignment
- using packed unaligned fields unsafely
- comparing object graph with reduced functionality
- measuring only construction

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
