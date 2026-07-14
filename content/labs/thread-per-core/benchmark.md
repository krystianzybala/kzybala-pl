# Thread-per-core architecture — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Measured</p>
  <p>JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10 cores: 8P + 2E),
  64 GB unified memory, macOS 26.5.1, arm64. Rust: Criterion 0.5.1, rustc
  1.88.0, same machine. Java: 1 fork, 5 warmup + 10 measurement iterations
  of 1 second each, <code>Mode.Throughput</code>, 4 threads. Rust: default
  Criterion sampling (100 samples), each sample spawning 4 real threads
  doing 20,000 increments each, then joining. Single developer machine
  with ordinary desktop load running alongside — not a dedicated,
  thermally-stable rig, no CPU affinity pinning.</p>
</div>

## Method

4 threads, two architectures, otherwise identical work: increment a
counter 20,000 times per thread (Rust) or run continuously for the
measurement window (Java). <strong>Shared pool:</strong> all 4 threads
increment a randomly chosen partition (0–3) in one array guarded by a
single lock. <strong>Owned partitions:</strong> each thread increments
only its own, unshared counter — no lock, no shared memory at all between
threads.

## Measured data (this run — not a portable claim)

**Java (JMH, ops/ms, 4 threads, higher is better):**

| Benchmark | Throughput | 99.9% CI |
|---|---|---|
| `sharedPoolIncrement` | 48,783.530 ops/ms | ±3,934.041 |
| `ownedPartitionIncrement` | 1,861,211.873 ops/ms | ±9,726.822 |

**Rust (Criterion, 80,000 increments total per sample — 4 threads × 20,000):**

| Benchmark | Median time | Derived ops/ms |
|---|---|---|
| `shared_pool_increment_4_threads` | 2.3671 ms | ≈33,798 |
| `owned_partition_increment_4_threads` | 65.375 µs | ≈1,223,982 |

## What this shows

**Owned partitions outperform the shared, lock-guarded pool by roughly
38× in Java (1,861,212 vs. 48,784 ops/ms) and roughly 36× in Rust (derived
1,223,982 vs. 33,798 ops/ms) — consistent in <em>magnitude</em> across both
languages, even though their absolute baselines differ (as with every
cross-language comparison on this site — see the SPSC Ring Buffer lab's
benchmark notes on why absolute numbers don't transfer across language/JIT
boundaries).** This is the concrete cost of one shared lock serializing 4
threads' worth of work down to roughly one thread's worth of throughput —
not a subtle effect, and not particular to Java or Rust: it's what a
single point of shared, contended state costs regardless of language.

**This benchmark isolates the lock/no-lock difference in the purest
possible case** — a trivial increment, with no actual request-processing
work, handoff, or queueing on either side. A real thread-per-core system's
end-to-end advantage over a shared pool will be smaller in relative terms
once real per-request work dominates the trivial per-increment cost shown
here, and will also be offset by whatever handoff/backpressure cost the
interactive model above demonstrates conceptually — this number shows the
mechanism's raw cost, not a deployable system's expected speedup.

## Raw data and reproduction

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — core count and
contention pattern both change this curve.
