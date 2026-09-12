# MPSC queues and producer contention — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions. They are retained only
  to illustrate the likely shape of the effect and are not
  publication-grade performance evidence. Canonical results for this
  laboratory are collected separately on the dedicated native-Linux
  benchmark host with explicit CPU placement, environment capture,
  correctness gates, independent JVM forks, and profiler evidence where
  required (<code>docs/measurement-environments.md</code>).</p>
  <p>Run details: JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10
  cores: 8P + 2E), 64 GB unified memory, macOS 26.5.1, arm64. Rust:
  Criterion 0.5.1, rustc 1.88.0, same machine. Java: smoke-run parameters
  (0 warmup, 1×200ms measurement) — wiring check only, zero statistical
  value. Rust: Criterion quick mode (reduced sample count). Ordinary
  desktop load alongside, no CPU affinity pinning, no control over
  performance- vs. efficiency-core scheduling.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist for every required variant, but no canonical evidence from
the dedicated native-Linux benchmark host has been imported for this
laboratory yet — no verified performance conclusion is available, and the
development numbers below are not a substitute.

## Operation definition

One benchmark operation is one full run of the fan-in protocol at a fixed
producer count: spawn all producer threads and the consumer thread, run
every producer to completion (`ITEMS_PER_PRODUCER` items each), drain the
consumer to the exact expected total, join every thread. Both languages
use the identical definition — setup (fixture constant selection) happens
outside the timed region; dataset generation is not applicable (payloads
are derived deterministically from producer id and sequence number, not
generated). Producer count is a JMH `@Param`/Criterion `BenchmarkId`
axis, never mixed within one sample.

## Method

All five required variants are wired and benchmarked in both languages.
**The library-queue variant is not directly comparable to the other
four**: it is unbounded (producers are never rejected or backpressured),
while the shared-MPSC, batched-claim, mutex-queue and per-producer
fan-in variants are all bounded to `CAPACITY` slots and can pay real
backpressure-wait cost the library queue never does. A throughput number
alone would credit the library queue for a semantic difference, not a
mechanism difference — this is exactly the "comparing bounded and
unbounded semantics" trap this lab names. The bounded variants are
directly comparable to each other; the library queue is reported
alongside them as a labeled reference point, never merged into a ranking
that implies equivalent semantics.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op, lower is better; smoke-run
parameters, wiring check only):**

| Variant | 2 producers | 4 producers |
|---|---|---|
| Single shared MPSC | 488.3 | 1,921.7 |
| Batched claims | 194.9 | 313.2 |
| Per-producer fan-in | 242.1 | 589.5 |
| Mutex queue | 488.8 | 1,058.8 |
| Library queue (unbounded, not directly comparable) | 364.9 | 1,050.0 |

**Rust (Criterion `--quick`, median of a reduced sample count, µs):**

| Variant | 2 producers | 4 producers |
|---|---|---|
| Single shared MPSC | 239.9 | 1,363.8 |
| Batched claims | 74.8 | 154.2 |
| Per-producer fan-in | 194.1 | 375.4 |
| Mutex queue | 443.1 | 1,019.2 |
| Library queue (unbounded, not directly comparable) | 90.8 | 426.6 |

## What this shows (and does not)

**These two tables are not a Java-vs-Rust comparison.** Both used
wiring-check parameters (JMH: 0 warmup, 1×200ms measurement; Criterion:
`--quick`), chosen for speed, not statistical validity.

**The within-language shapes are directionally consistent with the
theory** in both languages: batched claims is the cheapest bounded
variant at both producer counts (fewer atomic RMWs on the shared claim
point), and the single shared MPSC degrades the most from 2 to 4
producers (contention on that same shared claim point growing with
producer count) — this ordering matching the mechanism's prediction is a
wiring sanity check, not evidence of the *magnitude* of the effect. A
real conclusion about magnitude, CAS failure rates, cache-transfer
counts, or fairness requires the native-Linux evidence run.

**Neither table reports fairness, occupancy or CAS-failure counts** —
those are captured by the harnesses (see java.md/rust.md) but are only
meaningful as evidence once imported from a controlled run; smoke-run
numbers here are throughput only, to keep this section from implying more
than a wiring check can support.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js mpsc-contention

# Smoke run (wiring check only — zero statistical value):
cd content/labs/mpsc-contention/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/mpsc-contention/code/rust && cargo bench -- --quick --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread
scheduling, core topology and physical-core count all change this curve;
the "8/physical-core producers" dataset point is exercised only on the
native-Linux evidence host, where the physical-core count is read from
the live topology rather than assumed.
