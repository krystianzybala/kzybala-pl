# Proposal: Java-Rust Interop with FFM Downcalls and Upcalls

## Intent

Build a complete advanced Performance Lab that answers:

> What is the real cost and safety boundary of calling Rust from Java and Java callbacks from Rust?

## Hypothesis

FFM removes JNI ceremony but not ABI, transition, pinning, lifetime or callback costs; coarse-grained calls can win while chatty FFI loses.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the I/O & IPC track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: pure Java baseline, pure Rust baseline, scalar downcall per item, batched downcall, zero-copy buffer downcall, Rust-to-Java upcall.
- Exercise these datasets/profiles: numeric transform, fixed-record validation, callback notification.
- Record at least these metrics: ns/call, ns/item, calls/message, bytes copied, allocation rate, p99 callback latency.
- Capture evidence using repository-supported subsets of: JMH, Criterion, JFR, perf, perfasm, cargo asm, sanitizers/miri where applicable.
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
- PLAB-305
- PLAB-501

## Expected Learning Outcomes

- design narrow stable ABI
- batch FFI work
- implement safe downcall/upcall lifetimes

## Known Benchmark Traps

- letting panic unwind across FFI
- passing invalid lifetimes
- benchmarking debug Rust library
- calling per field and blaming FFM
- silently copying buffers

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
