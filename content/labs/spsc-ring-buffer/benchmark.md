# SPSC ring buffer — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Measured</p>
  <p>JMH 1.37, OpenJDK 26.0.1 (HotSpot), Apple M1 Max (10 cores: 8P + 2E),
  64 GB unified memory, macOS 26.5.1, arm64. Rust: Criterion 0.5.1, rustc
  1.88.0, same machine. Java: 1 fork, 5 warmup + 10 measurement iterations
  of 1 second each, <code>Mode.Throughput</code>, <code>@Group</code> with
  one producer thread and one consumer thread pinned to a 1024-slot buffer
  for the whole run. Rust: default Criterion sampling (100 samples), each
  sample spawning a fresh producer thread and consumer thread over a
  1024-slot buffer moving 200,000 items, then joining both. Single
  developer machine with ordinary desktop load running alongside — not a
  dedicated, thermally-stable rig, no CPU affinity pinning, no control over
  performance- vs. efficiency-core scheduling.</p>
</div>

## Method

Both languages benchmark the same shape — one producer thread and one
consumer thread moving values through a 1024-slot ring buffer as fast as
each side can go — but **the two benchmarks measure different things, and
their numbers must not be compared directly.** JMH's `@Group` keeps both
threads alive for the entire 1-second measurement window and reports
steady-state throughput once both are running. Criterion's `b.iter(...)`
re-runs the whole closure — including `thread::spawn` and `.join()` for a
brand-new producer/consumer pair — on every one of its 100 samples, so its
reported time necessarily includes thread creation/teardown overhead that
the Java number does not.

## Measured data (this run — not a portable claim)

**Java (JMH, `@Group("spsc")`, ops/ms per side, higher is better):**

| Benchmark | Throughput | 99.9% CI |
|---|---|---|
| `spsc` (combined) | 24,850.079 ops/ms | ±412.206 |
| `spsc:produce` | 12,424.844 ops/ms | ±206.229 |
| `spsc:consume` | 12,425.235 ops/ms | ±205.978 |

**Rust (Criterion, full pipeline including thread spawn/join, 200,000 items per sample):**

| Metric | Value |
|---|---|
| Median pipeline time | 907.47 µs |
| Min–max (100 samples) | 864.16 µs – 955.78 µs |
| Derived items/ms (from median) | ≈220,392 |

## What this shows

**Producer and consumer throughput match almost exactly in the Java
run** (12,424.844 vs. 12,425.235 ops/ms) — expected for a correctly
balanced SPSC pipeline with no other bottleneck: neither side can outpace
the other by more than the buffer's capacity allows before the slower side
becomes the limiter, and here both sides are doing symmetric,
equally-cheap work (`tryProduce`/`tryConsume` are mirror images of each
other).

**The Rust number cannot be read as "faster than Java" here** — its
derived ~220,392 items/ms includes real `std::thread::spawn`/`join`
overhead paid once per 200,000-item sample, which the Java benchmark's
persistent-thread `@Group` methodology deliberately excludes so it can
measure pure steady-state throughput. A lower thread-overhead fraction
(larger item count per sample, or a persistent-thread harness matching
JMH's approach) would very likely change this number substantially. This
is exactly why the Method section above states the two are not directly
comparable, rather than presenting one combined cross-language table as
if they measured the same thing.

**Neither number is a "real-world" ring-buffer throughput figure** — both
benchmarks intentionally run the tightest possible spin-loop with no
actual payload processing on either side, isolating the ring buffer's own
mechanics from any application work. A real pipeline's throughput will be
governed by whatever the producer/consumer actually compute per item, not
by these numbers.

## Raw data and reproduction

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread
scheduling, core topology, and buffer capacity all change this curve.
