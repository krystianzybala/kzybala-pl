# Proposal: Object Serialization vs Fixed Binary Layout

## Intent

Build a complete intermediate Performance Lab that answers:

> How much latency and allocation are spent turning in-memory state into bytes and back?

## Hypothesis

Schema-driven fixed layouts and flyweights reduce allocation and copies compared with generic object serialization, at the cost of evolution discipline.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the I/O & IPC track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: generic object codec, ByteBuffer/manual codec, FFM flyweight codec, Rust owned decode, Rust borrowed decode.
- Exercise these datasets/profiles: small command, medium event, repeated fields, versioned optional field.
- Record at least these metrics: encode/decode ns/message, B/message, wire bytes, bytes copied, branch misses.
- Capture evidence using repository-supported subsets of: JMH, Criterion, JFR allocation, perf, perfasm, cargo asm.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-305

## Expected Learning Outcomes

- design stable fixed layouts
- measure encode/decode separately
- support version evolution explicitly

## Known Benchmark Traps

- comparing incompatible schemas
- excluding validation
- using compressed generic formats without reporting CPU
- hardcoding endianness silently

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
