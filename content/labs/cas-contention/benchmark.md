# CAS contention and backoff — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions. They are retained only
  to illustrate the likely shape of the effect (contention collapse as
  thread count grows) and are not publication-grade performance evidence.
  Canonical results for this laboratory are collected separately on the
  dedicated native-Linux benchmark host with explicit CPU placement,
  environment capture, correctness gates, independent JVM forks, and
  profiler evidence where required
  (<code>docs/measurement-environments.md</code>).</p>
  <p>Run details: JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10
  cores: 8P + 2E), 64 GB unified memory, macOS 26.5.1, arm64. Rust:
  Criterion 0.5.1, rustc 1.88.0, same machine. Java: 1 fork, 5 warmup + 10
  measurement iterations of 1 second each, <code>Mode.Throughput</code>.
  Rust: default Criterion sampling (100 samples, ~5 s target per
  benchmark). Ordinary desktop load alongside, no CPU affinity pinning, no
  control over performance- vs. efficiency-core scheduling.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute.

## Method

1,000,000 threads is obviously not being tested here — 1, 2, 4, and 8
threads, each repeatedly calling `compareAndSet`/`compare_exchange` in a
retry loop against one shared counter (`CasCounter`/`CasCounter`), measuring
aggregate throughput as thread count grows. Contrasted against
`SingleWriterCounter`, a plain (non-atomic) counter touched by exactly one
thread, run single-threaded.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, ops/ms, higher is better):**

| Threads | Throughput | 99.9% CI |
|---|---|---|
| 1 (CAS) | 139,812.901 | ±511.706 |
| 2 (CAS) | 29,390.379 | ±600.692 |
| 4 (CAS) | 14,219.198 | ±520.758 |
| 8 (CAS) | 4,520.314 | ±114.867 |
| Single-writer (1, no CAS) | 483,984.812 | ±1,782.614 |

**Rust (Criterion, derived ops/ms from batch time — batch sizes differ by
thread count so the raw batch times aren't directly comparable; ops/ms is):**

| Threads | Batch | Median batch time | Derived ops/ms |
|---|---|---|---|
| 1 (CAS) | 20,000 ops | 167.76 µs | 119,200 |
| 2 (CAS) | 40,000 ops | 1.1505 ms | 34,767 |
| 4 (CAS) | 80,000 ops | 4.1018 ms | 19,505 |
| 8 (CAS) | 160,000 ops | 26.912 ms | 5,945 |
| Single-writer (1, no CAS) | 160,000 ops | 50.356 µs | 3,177,654 |

## What this shows

**Contention collapse, unambiguously, in both languages.** Doubling thread
count from 1→2 roughly quarters Java's throughput (139,813 → 29,390 ops/ms)
and cuts Rust's by a similar factor — going from zero contention to any
contention at all is the steepest drop in both runs. Throughput keeps
falling, not plateauing, all the way to 8 threads: this is "contention
collapse" from the theory above, measured rather than asserted.

**The single-writer counter measured substantially faster than even
*uncontended* CAS** — roughly 3.5× in Java (483,985 vs. 139,813 ops/ms) and
about 27× in Rust (3,177,654 vs. 119,200 ops/ms derived). This gap is not
"CAS is slow" in isolation; it's the cost of an atomic
read-modify-write plus (in the Rust case) `SeqCst` ordering versus a plain
scalar increment with no synchronization at all — see "The single-writer
alternative" in the theory above for the trade-off this represents, not a
blanket recommendation.

**The Java/Rust gap at 1 thread is large (139,813 vs. 119,200 derived
ops/ms — actually close) but the single-writer gap is not** (483,985 vs.
3,177,654 derived ops/ms) — a plain, non-volatile Rust loop can be
optimized far more aggressively (register-resident, `SeqCst` Rust atomics
still impose real cost) than a Java `AtomicLong` under HotSpot in this
configuration. This is exactly why the lab does not claim a single
cross-language "backoff wins by N%" number — the absolute baseline differs
enormously by language and JIT/compiler behavior; the *shape* (collapse
under contention, single-writer's advantage) is what transfers.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js cas-contention

# Smoke run (wiring check only — zero statistical value):
cd content/labs/cas-contention/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/cas-contention/code/rust && cargo bench -- --quick --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread count
relative to physical core count changes this curve substantially.
