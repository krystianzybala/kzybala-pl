# Proposal: Syscall Amortization and Scatter/Gather I/O

## Intent

Build a complete advanced Performance Lab that answers:

> How much does per-message kernel crossing cost, and when do gathering writes or batching pay off?

## Hypothesis

Reducing syscall count improves throughput until buffering delay, copy construction or socket backpressure dominates.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the I/O & IPC track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: single small write, coalesced buffer, scatter/gather write, size/time bounded batch, backpressured receiver.
- Exercise these datasets/profiles: header plus payload, small messages, mixed sizes.
- Record at least these metrics: syscalls/message, ns/message, throughput, p99 queue delay, bytes copied, partial writes.
- Capture evidence using repository-supported subsets of: custom socket/file harness, strace summary, perf stat, JFR, Criterion support harness.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-501
- PLAB-504

## Expected Learning Outcomes

- implement correct vectored I/O
- account for buffering delay
- measure actual syscall reduction

## Known Benchmark Traps

- ignoring partial writes
- counting application calls instead of syscalls
- letting Nagle/coalescing differ silently
- measuring only throughput

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
