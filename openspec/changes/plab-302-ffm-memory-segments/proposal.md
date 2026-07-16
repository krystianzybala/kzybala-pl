# Proposal: Heap vs Off-Heap with FFM MemorySegment

## Intent

Build a complete advanced Performance Lab that answers:

> When does moving data off heap help, and what costs replace GC-managed objects?

## Hypothesis

FFM can provide explicit layout and lifetime control, but access checks, arenas, copying and ownership errors can erase gains.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Memory & Data track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: heap primitive array, confined segment, shared segment, sliced view, copied boundary crossing.
- Exercise these datasets/profiles: fixed records, large numeric buffers, binary frames.
- Record at least these metrics: ns/access, ns/record, allocation rate, RSS, cache misses, copy bytes.
- Capture evidence using repository-supported subsets of: JMH, JFR, perfasm, Criterion, perf, miri for safety checks where relevant.
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
- PLAB-106

## Expected Learning Outcomes

- choose heap vs off-heap from lifecycle
- use segments safely
- account for boundary copies

## Known Benchmark Traps

- off-heap as automatic speedup
- closing arena during use
- cross-thread confinement violations
- unsafe Rust without invariant documentation

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
