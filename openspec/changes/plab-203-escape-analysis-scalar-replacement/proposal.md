# Proposal: Escape Analysis and Scalar Replacement

## Intent

Build a complete advanced Performance Lab that answers:

> When is an allocation in Java source code not an allocation at runtime?

## Hypothesis

Non-escaping short-lived objects can be scalar-replaced by C2, while identity, opaque calls and uncommon control flow may force materialization.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the JVM Runtime track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: non-escaping aggregate, returned object, stored into field, passed to opaque call, identity hash or synchronization.
- Exercise these datasets/profiles: small coordinate/value object, result wrapper, temporary parser state.
- Record at least these metrics: B/op, allocations/op, ns/op, GC events, assembly allocation path.
- Capture evidence using repository-supported subsets of: JMH gc profiler, JFR, perfasm, Criterion, heap profiler where available.
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
- PLAB-202

## Expected Learning Outcomes

- prove allocation elimination
- recognize EA blockers
- design value-like APIs without superstition

## Known Benchmark Traps

- assuming every new allocates
- using disabled EA as production recommendation
- comparing stack Rust value to heap Java object with different semantics
- not checking materialization

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
