# Proposal: Virtual Threads vs Platform Threads vs Event Loops

## Intent

Build a complete advanced Performance Lab that answers:

> Which concurrency model fits blocking I/O, CPU-bound work and strict tail-latency paths?

## Hypothesis

Virtual threads improve blocking-task scalability but do not create CPU capacity or guarantee low-latency scheduling; event loops and pinned platform threads remain useful for owned hot paths.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Production Engineering track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: thread-per-request platform, virtual thread per task, fixed event loop, CPU-bound pool, mixed blocking and CPU workload.
- Exercise these datasets/profiles: simulated socket wait, short CPU stage, long CPU stage, lock/native pinning case.
- Record at least these metrics: throughput, p50/p99/p999, scheduler CPU, thread count, context switches, pinning events.
- Capture evidence using repository-supported subsets of: JFR, async-profiler wall/cpu, JMH or macro harness, Criterion/custom Rust harness, perf sched.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-102
- PLAB-405

## Expected Learning Outcomes

- select model per workload
- recognize virtual-thread pinning and CPU limits
- keep hot paths isolated

## Known Benchmark Traps

- using sleep as the only I/O model
- claiming virtual threads are low-latency threads
- adding Tokio only to win a chart
- mixing CPU capacity across models

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
