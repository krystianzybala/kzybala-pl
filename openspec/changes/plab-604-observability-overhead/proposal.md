# Proposal: Logging, Metrics and Profiling Overhead

## Intent

Build a complete advanced Performance Lab that answers:

> How much latency and allocation does observability add to the path it is supposed to explain?

## Hypothesis

Eager formatting, shared locks, labels and stack walking can dominate hot paths; guarded, structured and sampled instrumentation can retain evidence at bounded cost.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Production Engineering track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: no instrumentation, disabled eager logging, disabled lazy logging, synchronous logging, async bounded logging, metrics labels, sampled tracing.
- Exercise these datasets/profiles: single event, error burst, high-cardinality key, stack trace path.
- Record at least these metrics: ns/event, B/event, p99/p999, lost log count, CPU, contention.
- Capture evidence using repository-supported subsets of: JMH, Criterion, JFR, async-profiler, perf, allocation profiler.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-101
- PLAB-602

## Expected Learning Outcomes

- budget observability cost
- design bounded async logging
- select profiling modes with known overhead

## Known Benchmark Traps

- benchmarking logger initialization
- unbounded async queue
- hiding message loss
- high-cardinality metrics without memory accounting

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
