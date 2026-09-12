# Logging, Metrics and Profiling Overhead — benchmark methodology

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

Both languages define one benchmark operation per variant, over a 2000-event
sequence, with dataset generation outside the timed region. Every
variant's hot-path checksum is identical (asserted by the correctness
suite before any timing is trusted); the differences this benchmark
measures are entirely instrumentation overhead layered on top of that
identical work.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, ns/op, lower is better, 2000 events):**

| Variant | Time |
|---|---|
| `noInstrumentation` | 1,547 ns/op |
| `disabledLazyLogging` | 1,548 ns/op |
| `metricsLabels` | 38,562 ns/op |
| `disabledEagerLogging` | 69,702 ns/op |
| `asyncBoundedLogging` | 68,682 ns/op |
| `synchronousLogging` | 85,342 ns/op |
| `sampledTracing` | 1,077,612 ns/op |

**Rust (Criterion, ns, median of 10 samples, 2000 events):**

| Variant | Time |
|---|---|
| `no_instrumentation` | 5,411 ns |
| `disabled_lazy_logging` | 5,407 ns |
| `metrics_labels` | 26,741 ns |
| `disabled_eager_logging` | 183,280 ns |
| `async_bounded_logging` | 184,680 ns |
| `synchronous_logging` | 194,840 ns |
| `sampled_tracing` | 2,487,000 ns |

## What this shows

**`disabledLazyLogging` matches `noInstrumentation` almost exactly in both
languages, while `disabledEagerLogging` costs 45x more in Java (69,702 vs.
1,548 ns/op) and 34x more in Rust (183,280 vs. 5,407 ns)** — this is the
lab's central claim made numerically: a disabled log level guarantees
nothing is written, but only a guard placed *before* formatting actually
eliminates the formatting cost. Both languages show the same qualitative
signature despite very different absolute string-formatting costs, which
is expected — this is a mechanism claim (guard placement matters), never
a Java-vs-Rust speed claim.

**`sampledTracing` dominates every other variant by roughly two to three
orders of magnitude in both languages** — a real stack walk (12 frames
deep in this dataset) is genuinely expensive; sampling at 1-in-8 bounds
the *average* cost across many calls, but every individual sampled call
still pays this full price, exactly as theory.md describes.

**`asyncBoundedLogging` costs about as much as `disabledEagerLogging` in
this run, not less** — both pay the same formatting cost per call in this
dataset (the bounded queue in `errorBurst` accepts only the first 16 of
2000 events, but every one of the 2000 still pays `formatCount++` and
string construction before the accept/drop decision). This is a real,
disclosed finding, not a discrepancy: bounding the *queue* protects
memory and delivery cost, but does not by itself avoid formatting cost —
avoiding that requires combining bounded delivery with a guard, which
this lab's variant matrix deliberately keeps as two separable mechanisms
rather than merging them into one variant.

**Neither language's absolute nanosecond numbers should be read as a
portable claim** — these are single-machine, uncontrolled-desktop numbers;
the *relative* signature (guard placement matters enormously; sampling
trades coverage for bounded average cost; bounding a queue is not the
same as guarding formatting) is the mechanism worth trusting here.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js observability-overhead

# Smoke run (wiring check only — zero statistical value):
cd content/labs/observability-overhead/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms
cd content/labs/observability-overhead/code/rust && cargo bench -- --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. Re-run on your own
hardware — the relative ordering (guard-before-format beats
format-then-discard; sampling beats always-tracing) should hold, the
exact nanoseconds will not.
