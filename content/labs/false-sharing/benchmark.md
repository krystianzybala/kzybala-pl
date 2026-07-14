# False sharing — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Measured</p>
  <p>JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10 cores, no SMT),
  macOS, arm64. Rust: Criterion 0.5.1, rustc 1.88.0, same machine. Java: 1
  fork, 3 warmup iterations + 5 measurement iterations of 1 second each.
  Rust: 1 s warm-up, 30 samples targeting ~3 s collection. Single developer
  machine running inside a sandboxed environment — not a dedicated,
  thermally-stable benchmark rig; treat absolute numbers as illustrative of
  the effect's <em>shape</em>, not a portable performance claim, and expect
  run-to-run variance from scheduler/thermal noise on any machine, including
  yours.</p>
</div>

## Method

1. Two threads, each incrementing its own dedicated counter in a tight loop,
   for the shared, padded, and (Java only) `@Contended` layouts described in
   `java.md` and `rust.md`.
2. JMH `@Group`/`Scope.Group` (Java) and two joined `std::thread::spawn`
   handles (Rust) so both counters are actually written concurrently from
   separate OS threads — a single-threaded run of the same code shows no
   difference between layouts and is not a valid reproduction (see the
   investigation task in `theory.md`, step 3).
3. JMH reports throughput (ops/ms, higher is better) with JMH's own
   confidence interval per configuration. Criterion reports time per batch
   of 100,000 increments (lower is better) with an outlier-trimmed
   [min, median, max] over the sample set.

## Measured data (this run — not a portable claim)

**Java (JMH, ops/ms, higher is better):**

| Layout | Throughput | 99.9% CI |
|---|---|---|
| Shared counters | 68,869 | ±4,506 |
| Padded counters (manual) | 255,796 | ±32,251 |
| `@Contended` counters | 325,893 | ±7,616 |

**Rust (Criterion, time per 100,000-increment batch, lower is better):**

| Layout | Median | [min, max] |
|---|---|---|
| Adjacent atomics (shared line) | 1.0017 ms | [984.80 µs, 1.0228 ms] |
| `align(64)` counters (padded) | 263.44 µs | [262.21 µs, 265.07 µs] |

On this run: padding gave roughly a 3.7× throughput improvement in Java and
a 3.8× reduction in time-per-batch in Rust; `@Contended` counters slightly
outperformed manual padding in Java (325,893 ± 7,616 vs. 255,796 ± 32,251
ops/ms — non-overlapping 99.9% confidence intervals, so this ranking is not
measurement noise, though the margin is modest). These ratios — not the
absolute numbers — are the part worth trusting across machines; re-run the
project in `code/` on your own hardware and replace this table with your own
numbers before using either the ratio or the absolute figures for a real
design decision.

## Known limitations

- Single machine, single run per language — no cross-run variance data
  beyond the reported CIs, and no CPU pinning was used to isolate benchmark
  threads from OS scheduling noise.
- `@Contended` vs. manual padding is the closest comparison in this lab;
  treat it as directionally reliable, not precise to the percentage point.
- See `theory.md`'s "Limitations of this model" for how the interactive
  visualisation simplifies coherence-traffic timing relative to real
  hardware.

## Raw data and reproduction

The runnable benchmark projects are committed at `code/java/` (Maven +
JMH) and `code/rust/` (Cargo + Criterion) next to this file. Each has a
`README.md` with exact build/run commands. JMH and Criterion both emit
per-iteration raw samples (JMH: `-rf json`; Criterion:
`target/criterion/**/new/raw.csv` and the generated HTML report) — inspect
those rather than only the summary line before drawing conclusions from a
run.
