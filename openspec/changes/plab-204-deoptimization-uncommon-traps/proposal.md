# Proposal: Deoptimization and Uncommon Traps

## Intent

Build a complete advanced Performance Lab that answers:

> What happens when optimized code assumptions stop being true in production?

## Hypothesis

Profile-guided speculation can make common paths fast but profile shifts, class loading and uncommon cases can trigger deoptimization and tail spikes.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the JVM Runtime track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: stable type profile, profile shift after warm-up, rare exception path, late subtype loading, nullability shift.
- Exercise these datasets/profiles: strategy dispatch, parsing mixed records, rare validation failure.
- Record at least these metrics: p99/p999, deoptimization count, recompilation count, latency timeline, code-cache events.
- Capture evidence using repository-supported subsets of: JMH custom iteration logging, JFR, JITWatch, HotSpot logs, Criterion.
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
- PLAB-202

## Expected Learning Outcomes

- diagnose deoptimization
- design stable hot-path profiles
- include phase changes in latency analysis

## Known Benchmark Traps

- using artificial class loading without disclosure
- hiding warm-up phase transitions
- equating Rust branch misprediction with JVM deoptimization
- publishing only steady-state mean

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
