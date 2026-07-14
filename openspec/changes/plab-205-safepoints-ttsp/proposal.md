# Proposal: Safepoints and Time to Safepoint

## Intent

Build a complete advanced Performance Lab that answers:

> Why can a short stop-the-world operation still produce a long application pause?

## Hypothesis

Pause time includes both safepoint operation duration and time for threads to reach a safepoint; long loops and native sections can dominate TTSP.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the JVM Runtime track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: cooperative loop, long loop with sparse polls, thread in native call, many idle threads, allocation pressure trigger.
- Exercise these datasets/profiles: numeric loop, native sleep/downcall, thread fleet.
- Record at least these metrics: TTSP, safepoint operation time, application stopped time, p99 latency, thread count.
- Capture evidence using repository-supported subsets of: JFR, safepoint logging, async-profiler wall-clock, perf, custom Rust coordinator.
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
- PLAB-102

## Expected Learning Outcomes

- separate TTSP from operation time
- identify non-cooperative work
- reason about runtime coordination costs

## Known Benchmark Traps

- blaming GC for all pauses
- using obsolete flags without version checks
- claiming Rust has no pauses
- running with uncontrolled OS scheduling

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
