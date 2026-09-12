# Backpressure and Bounded Pipelines — benchmark methodology

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
  machine, reduced sample size (10) for a fast smoke pass. Ordinary
  desktop load alongside, no CPU affinity pinning.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute.

## Method

Both languages define one benchmark operation per policy: one full
deterministic-schedule simulation (`sustainedOverload`, or `hotKeySkew` for
`coalesceByKey`), with dataset generation outside the timed region. This
measures each policy's own **administrative overhead** — the cost of the
bookkeeping each policy does per admitted/rejected/dropped/superseded item
— not a live network system's real end-to-end request latency; that
distinction is why this lab's canonical metrics (sojourn time, end-to-end
p99/p999, recovery time) are the ones the native-Linux evidence run must
capture from a real threaded harness, not from this deterministic
simulation's wall-clock time.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op, lower is better):**

| Policy | Time |
|---|---|
| `boundedReject` | 2.768 µs/op |
| `loadShedding` | 3.035 µs/op |
| `unbounded` | 3.674 µs/op |
| `dropOldest` | 3.702 µs/op |
| `boundedBlock` | 6.143 µs/op |
| `coalesceByKey` | 10.355 µs/op |

**Rust (Criterion, µs, median of 10 samples):**

| Policy | Time |
|---|---|
| `bounded_reject` | 2.143 µs |
| `load_shedding` | 2.713 µs |
| `unbounded` | 3.194 µs |
| `drop_oldest` | 3.465 µs |
| `bounded_block` | 4.227 µs |
| `coalesce_by_key` | 7.161 µs |

## What this shows

**Both languages show the same relative ordering** —
`boundedReject`/`loadShedding` cheapest, `coalesceByKey` most expensive,
`boundedBlock` and `unbounded` in between — which is expected from the
mechanism, not a language effect: `boundedReject` does the least
bookkeeping (one length check, then either a push or a counter increment),
while `coalesceByKey` does a hash-map lookup-and-possibly-overwrite plus,
on first arrival, an insertion-order tracking update, for every single
item.

**`boundedBlock` costs noticeably more than `boundedReject`/`unbounded`
in both languages** — its extra backlog-draining loop at the start of
every tick, absent from every other policy, is real per-tick overhead this
simulation makes visible; it does not yet say anything about real
thread-parking cost, which only a genuinely concurrent harness can measure
(see Method above).

**Neither language's numbers should be read as a portable claim about
which overload policy is "fastest" in production** — this measures
simulator bookkeeping cost on 450 deterministic ticks on one uncontrolled
development machine, not real network I/O, real thread contention, or
real memory-allocation pressure under a genuine multi-producer load. The
*relative shape* (coalescing costs more per item; blocking costs more per
tick than rejecting) is the mechanism worth trusting here — the
comparison is not, and never will be, a Java-vs-Rust speed claim, since
both implementations run the identical deterministic algorithm.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js backpressure-bounded-pipelines

# Smoke run (wiring check only — zero statistical value):
cd content/labs/backpressure-bounded-pipelines/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms
cd content/labs/backpressure-bounded-pipelines/code/rust && cargo bench -- --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes
`target/criterion/**/new/raw.csv` and a generated HTML report. Re-run on
your own hardware — the relative ordering should hold, the exact
microseconds will not.
