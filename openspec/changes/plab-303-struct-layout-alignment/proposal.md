# Proposal: Struct Layout, Alignment and Padding

## Intent

Build a complete advanced Performance Lab that answers:

> How do field order, alignment and cache-line boundaries affect footprint and access cost?

## Hypothesis

Reordering fields and separating contended values can reduce padding or coherence traffic, while packed layouts may create unaligned access penalties.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Memory & Data track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: natural layout, poor field order, optimized field order, cache-line aligned, packed/unaligned controlled variant.
- Exercise these datasets/profiles: mixed primitive record, header plus payload, producer/consumer counters.
- Record at least these metrics: bytes/record, ns/access, cache-line crossings, unaligned load evidence.
- Capture evidence using repository-supported subsets of: JOL, FFM layouts, JMH, Criterion, offset_of/size_of, perfasm, cargo asm.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-301
- PLAB-302

## Expected Learning Outcomes

- construct explicit layouts
- reason about alignment and arrays
- avoid undefined behavior around packed data

## Known Benchmark Traps

- assuming field declaration order is universal
- unsafe references to packed fields
- padding away footprint without measuring array density
- using 64-byte lines as universal without documenting host

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
