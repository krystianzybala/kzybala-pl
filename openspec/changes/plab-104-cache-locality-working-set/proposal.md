# Proposal: Cache Locality and Working-Set Size

## Intent

Build a complete intermediate Performance Lab that answers:

> Why does the same loop collapse when the working set moves from L1 to L2, L3 and DRAM?

## Hypothesis

Access order and footprint determine cache-miss rates and memory-level parallelism more strongly than source-language syntax.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Measurement & CPU track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: sequential scan, random permutation, pointer chasing, blocked/tiled traversal.
- Exercise these datasets/profiles: L1-sized, L2-sized, LLC-sized, 2x LLC, large DRAM-resident.
- Record at least these metrics: ns/element, L1 misses, LLC misses, cache references, bandwidth, IPC.
- Capture evidence using repository-supported subsets of: JMH, Criterion, perf stat, perf c2c where available, async-profiler cache-miss profile.
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
- PLAB-102

## Expected Learning Outcomes

- reason in bytes rather than element counts
- identify cache-capacity transitions
- design locality-friendly traversal

## Known Benchmark Traps

- different dataset generation between languages
- page faults in measurement
- NUMA migration
- comparing element counts with different byte footprints

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
