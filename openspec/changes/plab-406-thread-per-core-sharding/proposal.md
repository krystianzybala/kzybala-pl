# Proposal: Thread-per-Core and Shared-Nothing Sharding

## Intent

Build a complete advanced Performance Lab that answers:

> Can ownership partitioning remove synchronization from the hot path?

## Hypothesis

Stable key-to-core sharding and single-writer state can reduce coherence and locks, but routing, skew and rebalancing become first-class costs.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Concurrency track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: shared concurrent map, fixed shards with mutex, single-writer thread-per-core, skewed key distribution, rebalance simulation.
- Exercise these datasets/profiles: account/order state updates, per-key counters, partitioned aggregation.
- Record at least these metrics: end-to-end p50/p99/p999, throughput, cross-core transfers, skew, queue depth, rebalance cost.
- Capture evidence using repository-supported subsets of: custom load harness, JFR, async-profiler, perf c2c, Criterion/custom Rust harness.
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
- PLAB-603

## Expected Learning Outcomes

- design single-writer shards
- handle hot-key skew
- include routing and recovery costs

## Known Benchmark Traps

- hiding routing cost
- using random keys that eliminate realistic skew
- pinning without topology disclosure
- calling shared-nothing globally lock-free

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
