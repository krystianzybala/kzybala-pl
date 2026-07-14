# Proposal: Deterministic Low-Latency Pipeline Capstone

## Intent

Build a complete capstone Performance Lab that answers:

> Can the techniques from the curriculum combine into a small, reproducible, bounded end-to-end runtime?

## Hypothesis

A fixed-layout ingest-to-decision pipeline with single-writer shards, bounded rings, zero-copy decode and explicit overload policy can maintain predictable latency under controlled load.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Production Engineering track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: naive object/queue pipeline, optimized Java pipeline, optimized Rust pipeline, overload profile, fault/restart profile.
- Exercise these datasets/profiles: 32B/128B/1KiB fixed events, uniform keys, hot-key skew, steady and burst load.
- Record at least these metrics: end-to-end p50/p99/p999, throughput, allocation rate, drops/rejects, CPU/core, cache misses, recovery time.
- Capture evidence using repository-supported subsets of: macro harness, HdrHistogram, JFR, async-profiler, perf stat/c2c, Criterion support tools.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- all reference labs
- PLAB-501
- PLAB-602
- PLAB-603
- PLAB-605

## Expected Learning Outcomes

- assemble a bounded runtime
- defend every latency claim with evidence
- explain where Java and Rust differ and where hardware dominates

## Known Benchmark Traps

- declaring a language winner from one host
- omitting correctness checks
- using unbounded queues
- hiding warm-up/startup
- hand-tuning one implementation only

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
