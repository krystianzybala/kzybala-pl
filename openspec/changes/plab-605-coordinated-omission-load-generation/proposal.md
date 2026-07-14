# Proposal: Coordinated Omission and Load Generation

## Intent

Build a complete advanced Performance Lab that answers:

> Why can a saturated system appear healthy when the load generator waits for each response before sending the next request?

## Hypothesis

Closed-loop generators under-report latency during stalls; open-loop or omission-corrected measurement exposes queueing and missed schedules.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Production Engineering track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: closed loop, open loop fixed rate, Poisson-like arrivals, omission-corrected recording, burst schedule.
- Exercise these datasets/profiles: periodic 10ms stall, GC/pause injection, bounded server overload.
- Record at least these metrics: service time, response time, missed schedules, p50/p99/p999, throughput, queue depth.
- Capture evidence using repository-supported subsets of: custom load harness, HdrHistogram, JFR, Criterion support code.
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
- PLAB-602

## Expected Learning Outcomes

- detect coordinated omission
- build honest open-loop tests
- report offered and achieved load separately

## Known Benchmark Traps

- sleep drift without correction
- using offered load different from reported load
- dropping missed sends silently
- mixing service and response time

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
