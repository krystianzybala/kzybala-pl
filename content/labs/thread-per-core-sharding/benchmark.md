# Thread-per-core and shared-nothing sharding — benchmark methodology

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

One benchmark operation is one full run of a mechanism/dataset pair to
completion: spawn every requester thread (and, for single-writer/
rebalance, every shard-owner thread), submit and process the entire
workload plan's requests, join every thread. Both languages use the
identical definition; setup (workload-plan construction) happens outside
the timed region for the shared-map and mutex-shards variants — for the
single-writer and rebalance variants, spawning the owner threads is
necessarily part of the measured operation, since those threads are the
mechanism being measured, not fixed infrastructure.

## Method

All required variants are wired and benchmarked in both languages, over
the per-key-counters dataset (uniform and skewed) — see theory.md's
disclosed "partitioned aggregation" scope gap. **Routing cost is not
hidden**: the single-writer variant's channel-based routing is measured
as part of its total operation cost, exactly like every other variant's
synchronization mechanism, per this lab's own "hiding routing cost" trap.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op, lower is better; smoke-run
parameters, wiring check only):**

| Variant | Time |
|---|---|
| Shared map, uniform | 200.6 |
| Shared map, skewed | 195.7 |
| Mutex shards, uniform | 255.7 |
| Mutex shards, skewed | 237.4 |
| Single-writer, uniform | 1,021.4 |
| Single-writer, skewed | 1,014.8 |
| Rebalance simulation | 1,203.9 |

**Rust (Criterion `--quick`, median of a reduced sample count, µs):**

| Variant | Time |
|---|---|
| Shared map, uniform | 164.9 |
| Shared map, skewed | 186.0 |
| Mutex shards, uniform | 118.6 |
| Mutex shards, skewed | 129.9 |
| Single-writer, uniform | 305.6 |
| Single-writer, skewed | 344.8 |
| Rebalance simulation | 409.4 |

## What this shows (and does not)

**These two tables are not a Java-vs-Rust comparison.** Both used
wiring-check parameters chosen for speed, not statistical validity.

**In both languages, the single-writer variant costs noticeably more
than the shared-map and mutex-shards variants for this specific
workload** — a genuine, disclosed finding, not a bug: this dataset's
per-item work (one counter increment) is cheap enough that the routing
queue's own overhead (channel send/receive — Java's
`ConcurrentLinkedQueue` allocates a node per `add()`; Rust's
`std::sync::mpsc` has its own per-message bookkeeping) exceeds the cost
of the lock contention it removes. This is the concrete version of this
lab's own "hiding routing cost" trap and its hypothesis's second half
("routing... become[s] a first-class cost") — the theory does not claim
single-writer designs always win, only that they change *where* the cost
lives, and this smoke run shows a case where that trade does not pay off.
A workload with more expensive per-item work (contended data structure
updates, not a single counter increment) would need to be measured
separately before drawing a different conclusion — exactly what the
native-Linux evidence run, not this smoke run, would be for.

**Skew's effect on aggregate throughput is not visible in these
single-number tables** — the skewed dataset's *hot-shard* saturation
(this lab's own visualization) is a per-shard phenomenon; these tables
report only the aggregate operation time, not per-shard occupancy or
throughput distribution, which the native-Linux evidence run's `perf
c2c`/async-profiler capture is meant to surface.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js thread-per-core-sharding

# Smoke run (wiring check only — zero statistical value):
cd content/labs/thread-per-core-sharding/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/thread-per-core-sharding/code/rust && cargo bench -- --quick --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread
scheduling, core topology and OS scheduler behavior all change this
curve, especially for the routing-queue-based single-writer variant.
