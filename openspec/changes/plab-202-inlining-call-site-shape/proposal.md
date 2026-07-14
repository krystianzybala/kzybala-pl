# Proposal: Inlining and Call-Site Shape

## Intent

Build a complete advanced Performance Lab that answers:

> How do monomorphic, bimorphic and megamorphic call sites change optimization opportunities?

## Hypothesis

Inlining unlocks constant propagation and scalar replacement, but call-site diversity and code-size budgets can block it.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the JVM Runtime track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: monomorphic interface call, bimorphic call, megamorphic call, manual switch/enum dispatch, oversized callee.
- Exercise these datasets/profiles: pricing functions, codec strategies, validation rules.
- Record at least these metrics: ns/call, instructions, code size, inlining decisions, i-cache misses.
- Capture evidence using repository-supported subsets of: JMH, Criterion, JITWatch, perfasm, cargo asm, perf stat.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-201

## Expected Learning Outcomes

- identify call-site shapes
- connect inlining to downstream optimization
- balance code size and specialization

## Known Benchmark Traps

- manual devirtualization without maintainability discussion
- forcing inline everywhere
- comparing trait object to Java sealed dispatch as identical
- ignoring code-cache/i-cache cost

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
