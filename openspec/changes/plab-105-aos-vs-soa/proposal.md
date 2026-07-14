# Proposal: Array of Structures vs Structure of Arrays

## Intent

Build a complete intermediate Performance Lab that answers:

> How does record layout affect cache density, SIMD potential and update cost?

## Hypothesis

SoA improves read-mostly scans over selected fields, while AoS can remain superior for whole-record operations and simpler ownership.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Measurement & CPU track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: AoS heap objects, AoS packed/off-heap, SoA primitive vectors, hybrid hot/cold split.
- Exercise these datasets/profiles: market-like quotes, positions with hot and cold fields, spatial points.
- Record at least these metrics: ns/record, bytes/record, cache misses, allocations, vectorization evidence.
- Capture evidence using repository-supported subsets of: JMH, Criterion, JOL, FFM layout inspection, perf, perfasm, cargo asm.
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

- choose layout from access patterns
- quantify padding and density
- explain hybrid layouts

## Known Benchmark Traps

- changing semantics across layouts
- forgetting padding
- measuring construction instead of scan
- assuming SoA always wins

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
