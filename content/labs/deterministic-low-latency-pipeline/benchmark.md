# Deterministic Low-Latency Pipeline Capstone — benchmark methodology

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
  cores: 8P + 2E), macOS 26.6.2, arm64. Rust: Criterion 0.5.1, same
  machine, reduced sample size (10) for a fast smoke pass.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute. This capstone's canonical evidence in particular
requires the real, multi-threaded, real-buffer implementation exercised
end-to-end (see java.md/rust.md for what the correctness fixture
deliberately does not model) — the numbers below measure only the
deterministic simulator's own bookkeeping cost.

## Method

Both languages define one benchmark operation per variant: one full
deterministic-schedule simulation, with dataset generation outside the
timed region. This measures each variant's own admission/routing/decode
bookkeeping cost, not real end-to-end network or thread-scheduling
latency — the canonical p50/p99/p999 latency claims this lab's design.md
requires come from the real threaded harness on the native-Linux host,
never from this deterministic simulator.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op):**

| Variant | Time |
|---|---|
| `naiveObjectQueue` | 3.155 µs/op |
| `optimized` | 4.909 µs/op |
| `overloadProfile` | 4.170 µs/op |
| `faultRestartProfile` | 3.783 µs/op |

**Rust (Criterion, µs, median of 10 samples):**

| Variant | Time |
|---|---|
| `overload_profile` | 1.413 µs |
| `fault_restart_profile` | 1.746 µs |
| `naive_object_queue` | 2.670 µs |
| `optimized` | 2.883 µs |

## What this shows

**`optimized` is not cheaper to simulate than `naiveObjectQueue`/
`naive_object_queue` in either language** — expected, and worth stating
plainly rather than glossing over: the deterministic simulator's
`optimized` path does strictly more bookkeeping (four separate shard
deques, per-shard capacity checks) than the naive path's one unbounded
deque. This is the correctness-fixture's *administrative* cost, not the
real allocation/GC/lock-contention cost the naive pipeline's actual boxed
`LinkedBlockingQueue` implementation pays in production — that real cost
is exactly what the native-Linux evidence run (JFR allocation profiling,
real threaded harness) is needed to demonstrate, and why this
illustrative run cannot be read as "the optimized pipeline isn't
actually better."

**`overloadProfile`/`overload_profile` and `faultRestartProfile`/
`fault_restart_profile` are cheaper than `optimized`/`optimized` in this
run** — both process the same `mediumEventsHotKeyBurst` dataset, but
`overloadProfile`'s shrunk capacity causes more admission checks to
short-circuit into drops (cheaper than a full enqueue), and
`faultRestartProfile`'s mid-run clear discards a batch of already-admitted
work rather than draining it — both are artifacts of the deterministic
bookkeeping shape, not claims about real recovery-time or drop-handling
cost.

**Neither language's numbers say anything about this capstone's actual
end-to-end latency claim** — that is exactly why "Canonical results:
Awaiting native-Linux measurement" above is not boilerplate for this lab
in particular: a deterministic simulator's own CPU cost was never this
lab's subject.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js deterministic-low-latency-pipeline

# Smoke run (wiring check only — zero statistical value):
cd content/labs/deterministic-low-latency-pipeline/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms
cd content/labs/deterministic-low-latency-pipeline/code/rust && cargo bench -- --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file.
