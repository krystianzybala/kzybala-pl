# Proposal: Clocks, Latency Histograms and Percentiles

## Intent

Build a complete foundational Performance Lab that answers:

> What does a nanosecond timestamp actually mean, and how should latency distributions be recorded without distorting the hot path?

## Hypothesis

Clock source, timestamp overhead, histogram resolution and sampling strategy materially affect p99 and p999 conclusions.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Measurement & CPU track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: timestamp every operation, sampled timestamps, coordinated omission corrected recording, per-thread histogram merge.
- Exercise these datasets/profiles: fixed-cost operation, bimodal synthetic latency, periodic pause injection, burst workload.
- Record at least these metrics: timer overhead, p50, p95, p99, p99.9, max, histogram footprint.
- Capture evidence using repository-supported subsets of: JMH, Criterion, HdrHistogram-compatible tooling, perf stat.
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
- basic statistics

## Expected Learning Outcomes

- interpret latency distributions
- separate service time from response time
- select histogram precision deliberately

## Known Benchmark Traps

- averages hiding tails
- mixing service time and response time
- negative or non-monotonic assumptions
- recording allocations in the measured path

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
