# Locks, spin waiting and parking — benchmark methodology

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

One benchmark operation is one full run of a wait-strategy variant to
completion: spawn every worker thread (1 for uncontended, `WORKER_COUNT`
otherwise), run each worker's `OPS_PER_WORKER` protected increments, join
every thread. Both languages use the identical definition; setup (fixture
constant selection) happens outside the timed region. This is a macro
measurement of the whole worker set's run, not a steady-state per-
acquisition cost — see the Method section below for why that matters for
the "long critical section" variant specifically.

## Method

All five required variants are wired and benchmarked in both languages,
over the shared-counter dataset only (see theory.md's disclosed
"small map update"/"handoff flag" scope gap). **CPU consumption and
context-switch counts are not captured by the smoke-run numbers below**
— JMH/Criterion wall-clock timing alone cannot distinguish "fast because
nobody waited" from "fast because waiters spun efficiently"; that
distinction requires `perf stat`/`perf sched` evidence from the
native-Linux host, which this lab's `.conf` requests via
`LAB_PROFILER_POLICY="stat"` but which is not available from this
development machine.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op, lower is better; smoke-run
parameters, wiring check only):**

| Variant | Time |
|---|---|
| Uncontended mutex | 71.5 |
| Short contended (4 workers) | 252.6 |
| Long critical section (4 workers) | 987.2 |
| CAS loop (4 workers) | 194.2 |
| Spin-then-park (4 workers) | 250.5 |

**Rust (Criterion `--quick`, median of a reduced sample count, µs):**

| Variant | Time |
|---|---|
| Uncontended mutex | 38.3 |
| Short contended (4 workers) | 239.9 |
| Long critical section (4 workers) | 1,208.8 |
| CAS loop (4 workers) | 395.7 |
| Spin-then-park (4 workers) | 906.3 |

## What this shows (and does not)

**These two tables are not a Java-vs-Rust comparison.** Both used
wiring-check parameters chosen for speed, not statistical validity.

**The within-language shapes are directionally consistent with the
theory** in both languages: uncontended mutex is by far the cheapest
(no waiting to explain away), and long critical section is the most
expensive contended variant (every worker holds the lock longer, so
every other worker's wait grows too) — this ordering matching the
mechanism's prediction is a wiring sanity check, not evidence of
magnitude. Notably, the CAS loop and spin-then-park orderings differ
between the two languages' smoke runs here (Rust's `cas_loop` is cheaper
than `spin_then_park`; Java's `casLoop` and `spinThenPark` are close to
each other) — this is exactly the kind of crossover the theory page
describes as contention- and host-dependent, and precisely why this lab
does not draw a conclusion from smoke-run numbers on a shared, unpinned
development machine.

**Neither table reports CAS-failure counts, park counts, or fairness** —
those are captured by the harnesses (see java.md/rust.md) but are only
meaningful as evidence once imported from a controlled run.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js locks-spin-parking

# Smoke run (wiring check only — zero statistical value):
cd content/labs/locks-spin-parking/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/locks-spin-parking/code/rust && cargo bench -- --quick --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread
scheduling, core topology and OS scheduler behavior all change this curve,
especially for the spin-then-park variant.
