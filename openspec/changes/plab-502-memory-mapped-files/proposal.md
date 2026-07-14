# Proposal: Memory-Mapped Files and Page Faults

## Intent

Build a complete advanced Performance Lab that answers:

> When does mmap remove copying, and when do page faults turn latency into a lottery?

## Hypothesis

Mapped access can simplify large-file reads and sharing, but cold pages, dirty writeback and mapping lifecycle dominate tail behavior.

## Current Context

This change adds a new laboratory to the planned curriculum.

The lab is part of the I/O & IPC track and must use the shared flow:

`theory → mechanism visualization → Java → Rust → benchmark → profiler evidence → exercises`

## Scope

- Teach the mechanism before showing benchmark numbers.
- Provide semantically equivalent Java and Rust implementations where applicable.
- Include these controlled variants: buffered read, warm mmap sequential, cold mmap sequential, random mapped access, mapped write plus flush.
- Exercise these datasets/profiles: 64 MiB file, larger-than-RAM optional profile, fixed records.
- Record at least these metrics: ns/record, major/minor faults, read bandwidth, p99 latency, RSS/page cache, flush time.
- Capture evidence using repository-supported subsets of: JMH/custom harness, Criterion/custom harness, perf stat, page-fault counters, iostat where available.
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
- PLAB-305

## Expected Learning Outcomes

- separate warm and cold mapped performance
- account for faults and flush
- use mapping lifetimes safely

## Known Benchmark Traps

- dropping caches without root/disclosure
- confusing page cache with process memory
- excluding first touch
- depending on forced unmapping hacks

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
