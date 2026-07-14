# Proposal: Zero-Copy Binary Parsing and Views

## Intent

Build a complete advanced Performance Lab that answers:

> How can a decoder expose typed fields without copying payloads or allocating object graphs?

## Hypothesis

Views over validated immutable bytes can eliminate copies and allocations, but lifetime, endian, bounds and mutation rules must be explicit.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Memory & Data track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: copying decoder, object-building decoder, validated zero-copy view, lazy field decode, mutable in-place update controlled variant.
- Exercise these datasets/profiles: fixed header plus variable payload, nested repeated fields, UTF-8 field.
- Record at least these metrics: ns/message, B/message, bytes copied, branch misses, validation cost.
- Capture evidence using repository-supported subsets of: JMH, Criterion, JFR allocation profile, perf, perfasm, cargo asm, miri/fuzzing.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-302
- PLAB-303

## Expected Learning Outcomes

- implement lifetime-safe views
- separate validation from access
- measure actual bytes copied

## Known Benchmark Traps

- returning views past buffer lifetime
- skipping validation
- using invalid UTF-8 unchecked
- claiming zero-copy while converting strings

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
