# Object serialization vs fixed binary layout — benchmark methodology

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
  arm64. Java: 1 fork, 2 warmup + 3 measurement iterations of 300ms each
  (deliberately short — a wiring smoke, not a controlled measurement),
  <code>Mode.AverageTime</code>. Rust: Criterion 0.5.1, default sampling
  (100 samples, 3s warm-up, 5s measurement window per benchmark), same
  machine, <code>[profile.bench] inherits = "release"</code>.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available, and the development numbers below are
not a substitute.

## Operation definitions

**Java:** one benchmark operation is exactly one `encode(EventRecord)` or
`decode(byte[])`/`decode(byte[]) -> field access` call for one dataset
profile, with the input record and (for decode) the pre-encoded wire bytes
constructed in `@Setup`, outside the timed region. `@Param` selects the
dataset profile so each JMH trial measures exactly one profile, never
mixed.

**Rust:** the identical unit of work — one `encode(&Event)` or
`decode_owned(&wire)`/`EventView::device_id()` call — with the `Event`
and pre-encoded `wire` array built once outside `b.iter(...)`, matching the
Java definition's setup/timing split.

## What this shows (development run only)

**Java, `mediumEvent` profile, ns/op (lower is better):**

| Benchmark | Time | Error (99.9% CI) |
|---|---|---|
| `genericEncode` | 626,252 | ±141,820 |
| `genericDecode` | 3,775,075 | ±288,023 |
| `byteBufferEncode` | 6,430 | ±2,216 |
| `byteBufferDecode` | 7,246 | ±9,234 |
| `ffmEncode` | 732,541 | ±295,348 |
| `ffmDecode` | 141,642 | ±786,464 |
| `ffmDecodeFieldOnly` | 191,118 | ±793,695 |

**Rust, Criterion default sampling, ns/op (lower is better):**

| Benchmark | Time (point estimate) |
|---|---|
| `encode_medium_event` | 25.7 |
| `decode_owned_medium_event` | 7.92 |
| `decode_borrowed_device_id_only_medium_event` | 1.56 |
| `encode_repeated_fields` | 25.7 |
| `decode_owned_repeated_fields` | 7.88 |
| `decode_borrowed_device_id_only_repeated_fields` | 1.57 |

**The generic codec's cost is dominated by schema description, exactly as
theory.md predicts** — `genericEncode`/`genericDecode` are roughly two to
three orders of magnitude slower than the fixed-layout Java variants in
this run, consistent with paying for a class descriptor and reflective
field walk on every single message rather than any inherent property of
"Java object serialization" in general.

**The `ffmEncode`/`ffmDecode` numbers in this run carry very high relative
error (CI wider than the point estimate) and are almost certainly
dominated by JIT warm-up noise from the deliberately short 300ms/2-warmup
smoke profile, not a real per-call cost** — `Arena.ofConfined()`/
`Arena.ofAuto()` allocation and JIT compilation of the FFM access path are
exactly the kind of cost a 2-iteration warmup does not stabilize. This
number must not be read as "FFM is slower than ByteBuffer here"; a
properly warmed, multi-fork run is required before any such comparison is
trustworthy, which is precisely why this is a development smoke run and
not canonical evidence.

**The Rust owned-vs-borrowed decode gap (≈7.9 ns vs ≈1.56 ns) is the
clearest illustration of the flyweight mechanism in this dataset** — a
single-field read through `EventView` costs roughly a fifth of a full
`decode_owned` call, consistent with the theory that a flyweight defers
(and in this case entirely avoids) the cost of materializing fields the
caller never asked for.

**Java and Rust numbers must not be compared directly against each
other** — different JVM/JIT warm-up regime, different measurement harness
(JMH vs Criterion), and Java's smoke profile used a deliberately short
warm-up specifically to keep the wiring check fast, not to produce a
comparable measurement. Real cross-language framing (if any) belongs to
the native-Linux canonical run, not this table.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
cd content/labs/fixed-binary-serialization/code/java && mvn test
cd content/labs/fixed-binary-serialization/code/rust && cargo test --release

# Smoke run (wiring check only — zero statistical value):
cd content/labs/fixed-binary-serialization/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/fixed-binary-serialization/code/rust && cargo bench
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread
scheduling, JIT warm-up regime, and core topology all change this curve.
