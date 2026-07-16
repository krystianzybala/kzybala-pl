# Proposal: CPU Affinity, NUMA and IRQ Placement

## Intent

Build a complete expert Performance Lab that answers:

> How much variance comes from where threads, memory and interrupts run rather than from application code?

## Hypothesis

Pinning and first-touch placement can reduce migration and remote-memory noise, but incorrect topology choices or shared IRQs can make results worse.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the Production Engineering track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: unpinned baseline, pinned isolated core, SMT siblings, same NUMA node, remote NUMA node, IRQ interference profile where safely configurable.
- Exercise these datasets/profiles: SPSC handoff, memory scan, UDP ingest.
- Record at least these metrics: context switches, CPU migrations, remote NUMA accesses, p99/p999, cache transfers, interrupt rate.
- Capture evidence using repository-supported subsets of: lscpu/numactl metadata, perf stat, perf c2c, taskset/affinity tools, JFR.
- Publish raw data, environment metadata, commands and explicit limitations.
- Add diagnosis and implementation exercises with verifiable success criteria.

## Non-Goals

- Declare a universal Java-versus-Rust winner.
- Publish invented, hand-transcribed or cherry-picked benchmark values.
- Optimize away correctness, validation, lifecycle or memory-safety guarantees merely to improve a chart.
- Require privileged host tuning for the default smoke/development experience.
- Add unrelated frameworks or dependencies when existing repository mechanisms are sufficient.

## Prerequisites

- PLAB-104
- PLAB-401
- PLAB-406

## Expected Learning Outcomes

- capture hardware topology
- apply pinning safely
- distinguish application gains from placement artifacts

## Known Benchmark Traps

- hardcoding CPU ids
- requiring root for default run
- claiming results portable across topology
- pinning JVM GC/compiler threads indiscriminately

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
