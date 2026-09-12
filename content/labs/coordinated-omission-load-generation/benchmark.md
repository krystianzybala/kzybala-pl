# Coordinated Omission and Load Generation — benchmark methodology

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
not a substitute.

## Method

Both languages implement the identical discrete-event recurrence on a
virtual (logical) clock — no real threads, no real sleeping. The numbers
below measure the *simulator's own* per-call CPU cost (how expensive it is
to run 600 requests through each variant's admission/recurrence logic),
not real network or thread-scheduling latency; this is a benchmark of the
measurement machinery itself, appropriate for a lab whose subject is
measurement methodology.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op, 600 requests):**

| Variant | Time |
|---|---|
| `closedLoop` | 0.410 µs/op |
| `omissionCorrectedRecording` | 0.914 µs/op |
| `openLoopFixedRate` | 3.622 µs/op |
| `burstSchedule` | 3.985 µs/op |
| `openLoopUnderSustainedOverload` | 6.748 µs/op |
| `poissonLikeArrivals` | 130.395 µs/op |

**Rust (Criterion, µs, median of 10 samples, 600 requests):**

| Variant | Time |
|---|---|
| `closed_loop` | 0.213 µs |
| `omission_corrected_recording` | 0.695 µs |
| `open_loop_under_sustained_overload` | 1.572 µs |
| `open_loop_fixed_rate` | 2.186 µs |
| `burst_schedule` | 2.186 µs |
| `poisson_like_arrivals` | 82.970 µs |

## What this shows

**`poissonLikeArrivals`/`poisson_like_arrivals` is dramatically slower
than every other variant in both languages (roughly 30-380x)** — this is
a disclosed implementation artifact, not a finding about Poisson arrival
processes: its schedule function recomputes the cumulative sum of gaps
from scratch on every lookup (`O(n)` per call, `O(n²)` for the whole run),
a deliberate simplicity trade-off for a 600-request dataset rather than
maintaining a precomputed schedule array. A production load generator
would precompute the full arrival schedule once; this lab's code
prioritizes a short, obviously-correct implementation over that
optimization, and says so here rather than presenting the number without
context.

**`closedLoop` is the cheapest variant to simulate in both languages** —
expected, since it does no admission bookkeeping at all (no deque, no
capacity check) — but this administrative cheapness is exactly the
opposite of what closed-loop measurement's *honesty* looks like: theory.md's
whole point is that this cheap-to-simulate variant is the one that
structurally cannot see queueing, not that it's somehow the better choice.

**Neither language's numbers say anything about real network load
generators** — this is simulator bookkeeping cost on a deterministic,
in-memory recurrence, not real socket I/O, real thread scheduling, or a
real server's actual latency. The comparison is not, and never will be, a
Java-vs-Rust speed claim: both implementations run the identical
recurrence.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js coordinated-omission-load-generation

# Smoke run (wiring check only — zero statistical value):
cd content/labs/coordinated-omission-load-generation/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms
cd content/labs/coordinated-omission-load-generation/code/rust && cargo bench -- --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file.
