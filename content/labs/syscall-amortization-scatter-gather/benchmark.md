# Syscall amortization and scatter/gather I/O — benchmark methodology

<div class="disclosure measured">
  <p class="disclosure-kind">Illustrative development run</p>
  <p>These preliminary values were captured on an Apple M1 Max developer
  workstation under uncontrolled desktop conditions, over TCP loopback
  (127.0.0.1) only. They are retained only to illustrate the likely shape
  of the effect and are not publication-grade performance evidence.
  Canonical results for this laboratory are collected separately on the
  dedicated native-Linux benchmark host with explicit CPU placement,
  environment capture, correctness gates, independent JVM forks, and
  profiler evidence where required (<code>docs/measurement-environments.md</code>).
  <code>writeCalls</code>/<code>write_calls</code> below is an
  application-level proxy for kernel crossings, never a verified syscall
  count — real syscall counts require <code>strace -c</code> on the
  native-Linux host.</p>
  <p>Run details: JMH 1.37, OpenJDK 22 (HotSpot), Apple M1 Max, macOS,
  arm64, TCP loopback. Java: 1 fork, 1 warmup + 2 measurement iterations
  of 800ms each (a wiring smoke, not a controlled measurement),
  <code>Mode.AverageTime</code>. Rust: Criterion 0.5.1, default sampling
  (100 samples), same machine, <code>[profile.bench] inherits = "release"</code>.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet. No verified
performance conclusion is available, and the development numbers below
are not a substitute.

## Operation definitions

**Java:** one benchmark operation is exactly one full run over the
declared message count for one variant (`singleSmallWrite`,
`coalescedBuffer`, `scatterGatherWrite`, `sizeBoundedBatch`) at one
dataset profile, with the receiver spawned on a background thread and
setup performed in `@Setup`, outside the timed region.

**Rust:** the identical unit of work — one full run over the same
message count per variant per profile — with the receiver spawned on a
background thread, matching the Java definition's setup/timing split.

## What this shows (development run only)

**Java, `headerPlusPayload` profile (2,000 messages, 256B payload), ms for the full run (lower is better):**

| Benchmark | Time | write() calls |
|---|---|---|
| `singleSmallWrite` | 4.86 | 4,000 (2/message) |
| `scatterGatherWrite` | 2.80 | 2,000 (1/message) |
| `coalescedBuffer` | 2.23 | 2,000 (1/message) |
| `sizeBoundedBatch` | 0.63 | 100 (1 per 20-message batch) |

**Rust, `header_plus_payload` profile (2,000 messages, 256B payload), Criterion point estimate (lower is better):**

| Benchmark | Time |
|---|---|
| `single_small_write_header_plus_payload` | 4.20 ms |
| `size_bounded_batch_header_plus_payload` | 2.97 ms |
| `coalesced_buffer_header_plus_payload` | 2.87 ms |
| `scatter_gather_write_header_plus_payload` | 2.74 ms |

**The write-call-count reduction shows up clearly in Java: batching
(100 calls vs. 4,000 for single-small-write) also shows the largest
speedup (≈7.7x) in this run** — consistent with theory.md's prediction
that amortizing kernel crossings across a batch is where the biggest
mechanical win is, though this is one development-machine run over
loopback, not a controlled measurement.

**Rust's batching variant does not show the same dramatic advantage in
this run** — `size_bounded_batch` is close to `coalesced_buffer` and
`scatter_gather_write` rather than dramatically faster, unlike Java's
result. This is a real, disclosed discrepancy in this specific
development run, not a claim that batching "doesn't work" in Rust — it is
exactly the kind of language/runtime-specific effect (JIT warm-up regime
differs fundamentally from an ahead-of-time compiled binary, and each
Criterion iteration reopens a fresh loopback connection whose accept/
connect overhead may dominate more evenly across variants on this host)
that native-Linux, multi-repetition evidence is needed to explain rather
than speculate about from one smoke run.

**Java and Rust numbers must not be compared directly against each
other** — different JVM/JIT warm-up regime vs. Rust's ahead-of-time
compiled release binary, and Java's smoke profile used deliberately short
warm-up specifically to keep the wiring check fast. Real cross-language
framing (if any) belongs to the native-Linux canonical run.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
cd content/labs/syscall-amortization-scatter-gather/code/java && mvn test
cd content/labs/syscall-amortization-scatter-gather/code/rust && cargo test --release

# Smoke run (wiring check only — zero statistical value):
cd content/labs/syscall-amortization-scatter-gather/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/syscall-amortization-scatter-gather/code/rust && cargo bench
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits
per-iteration raw samples (`-rf json`); Criterion writes
`target/criterion/**/new/raw.csv` and a generated HTML report. Re-run on
your own hardware — loopback network-stack behavior, TCP buffer sizing,
and CPU scheduling all change this curve.
