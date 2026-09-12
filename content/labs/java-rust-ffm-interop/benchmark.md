# Java-Rust interop with FFM downcalls and upcalls — benchmark methodology

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
  <p>Run details: JMH 1.37, OpenJDK 22 (HotSpot), Apple M1 Max, macOS,
  arm64, native library built via <code>cargo build --release</code>. Java:
  1 fork, 1 warmup + 2 measurement iterations of 800ms each (a wiring
  smoke, not a controlled measurement), <code>Mode.AverageTime</code>.
  Rust: Criterion 0.5.1, default sampling (100 samples), same machine,
  release profile.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist — including real downcalls and upcalls into a compiled Rust
`cdylib`, verified by both languages' test suites — but no canonical
evidence from the dedicated native-Linux benchmark host has been imported
for this laboratory yet. No verified performance conclusion is available,
and the development numbers below are not a substitute.

## Operation definitions

**Java:** one benchmark operation is exactly one full pass transforming a
10,000-element `double[]` for one variant (`pureJavaBaseline`,
`scalarDowncallPerItem`, `batchedDowncall`, `zeroCopyBufferDowncall`,
`rustToJavaUpcall`), with array/segment setup performed once in `@Setup`,
outside the timed region, plus one untimed warm-up call so downcall-stub
JIT compilation isn't attributed to the first measured sample.

**Rust:** the "pure Rust baseline" is the identical computation over the
same-shaped data, measured entirely within Rust with no FFI or JVM
involvement at all — not a benchmark of any Java-facing variant.

## What this shows (development run only)

**Java, ns for one full 10,000-element pass (lower is better):**

| Benchmark | Time |
|---|---|
| `zeroCopyBufferDowncall` | 1,026 |
| `pureJavaBaseline` | 2,689 |
| `batchedDowncall` | 8,952 |
| `scalarDowncallPerItem` | 144,320 |
| `rustToJavaUpcall` | 1,034,197 |

**Rust, Criterion point estimate, no FFI involved (lower is better):**

| Benchmark | Time |
|---|---|
| `pure_rust_baseline_batch` | ~1.01 µs (1,010 ns) |
| `pure_rust_baseline_scalar` | ~9.38 µs (9,380 ns) |

**The full gradient across Java's five variants matches this lab's
hypothesis shape closely, in this run** — zero-copy is cheapest,
pure-Java sits close behind it with no FFI at all, batching costs roughly
3-4x pure-Java (paying two array copies for one FFI crossing), the scalar
per-item downcall costs roughly 50x pure-Java (10,000 separate FFI
crossings), and the upcall costs roughly 7x the scalar downcall (10,000
separate crossings back into the JVM, which this run suggests is more
expensive per-crossing than the equivalent downcall direction). This is
one development-machine run, not a controlled measurement, but the
*ordering* is exactly what "coarse-grained calls can win while chatty FFI
loses" predicts.

**The batched-vs-zero-copy gap (8,952 ns vs. 1,026 ns) isolates the copy
cost specifically** — both call the identical native function; the
~7,900 ns difference in this run is consistent with theory.md's claim
that copying a 10,000-element array in and back out, not the FFI crossing
itself, is what the batched variant pays for that zero-copy avoids.

**Java and Rust numbers must not be compared directly against each
other, and neither must "pure Java" vs. "pure Rust"** — different
JIT/warm-up regime vs. an ahead-of-time compiled release binary, and this
lab's own non-goal explicitly forbids a Java-versus-Rust winner claim;
these two baselines exist to bound each language's own FFI variants, not
to be read against each other.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing (builds the native
# library automatically if it isn't already present):
cd content/labs/java-rust-ffm-interop/code/java && mvn test
cd content/labs/java-rust-ffm-interop/code/rust && cargo test --release

# Smoke run (wiring check only — zero statistical value):
cd content/labs/java-rust-ffm-interop/code/rust && cargo build --release
cd content/labs/java-rust-ffm-interop/code/java && mvn -q -DskipTests package && \
  java --enable-native-access=ALL-UNNAMED -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/java-rust-ffm-interop/code/rust && cargo bench
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo, built as a `cdylib` + Criterion) next to this file.
JMH emits per-iteration raw samples (`-rf json`); Criterion writes
`target/criterion/**/new/raw.csv` and a generated HTML report. Re-run on
your own hardware — JIT warm-up regime, native-library loading cost, and
CPU topology all change this curve.
