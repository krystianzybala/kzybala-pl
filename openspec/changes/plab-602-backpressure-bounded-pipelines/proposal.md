# Proposal: Backpressure and Bounded Pipelines

## Intent

Build a complete advanced Performance Lab that answers:

> How should a low-latency system behave when producers are faster than consumers?

## Hypothesis

Bounded queues make overload visible and controllable; unbounded buffering preserves throughput briefly while destroying latency and memory predictability.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Production Engineering track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: unbounded queue anti-pattern, bounded reject, bounded block, drop oldest/newest, coalesce by key, load shedding.
- Exercise these datasets/profiles: steady below capacity, short burst, sustained overload, hot-key skew.
- Record at least these metrics: queue depth, sojourn time, end-to-end p99/p999, drop/reject rate, RSS, recovery time.
- Capture evidence using repository-supported subsets of: custom load harness, HdrHistogram, JFR, async-profiler, Criterion support harness.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-403
- PLAB-404
- PLAB-102

## Expected Learning Outcomes

- design explicit overload behavior
- measure queueing separately
- recover predictably after overload

## Known Benchmark Traps

- reporting only successful requests
- hiding drops
- using infinite producer retries
- comparing different overload policies as equivalent

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
