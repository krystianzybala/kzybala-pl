# Proposal: Shared-Memory IPC

## Intent

Build a complete advanced Performance Lab that answers:

> How can two processes exchange fixed messages without sockets or payload copies?

## Hypothesis

A mapped SPSC protocol can achieve low one-way latency, but crash recovery, ownership, memory ordering and versioning are harder than the happy-path benchmark.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the I/O & IPC track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: pipe/socket baseline, shared memory copy payload, shared memory slot view, batched messages, process restart/recovery scenario.
- Exercise these datasets/profiles: fixed 32-byte/128-byte/1KiB messages, steady and burst load.
- Record at least these metrics: one-way p50/p99/p999, round-trip latency, messages/s, CPU/process, fault/recovery time.
- Capture evidence using repository-supported subsets of: custom process harness, HdrHistogram, perf, JFR, Criterion support utilities.
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
- PLAB-403
- PLAB-502

## Expected Learning Outcomes

- implement versioned shared-memory protocol
- validate cross-process publication
- measure recovery and lifecycle

## Known Benchmark Traps

- using same-process threads and calling it IPC
- missing cross-process memory-order proof
- leaking stale shared segments
- ignoring version mismatch and crash state

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
