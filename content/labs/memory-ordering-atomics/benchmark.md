# Memory Ordering: VarHandles and Rust Atomics — benchmark methodology

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
  cores: 8P + 2E), 64 GB unified memory, macOS 26.5.1, arm64. Rust:
  Criterion 0.5.1, rustc 1.88.0, same machine. Java: 1 fork, 5 warmup + 10
  measurement iterations of 1 second each (smoke run shown used 0 warmup +
  1 iteration of 200ms, wiring check only). Rust: Criterion quick mode
  (reduced sample count), 1s warmup/measurement. Ordinary desktop load
  alongside, no CPU affinity pinning, no control over performance- vs.
  efficiency-core scheduling.</p>
</div>

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist for every required variant and dataset, but no canonical
evidence from the dedicated native-Linux benchmark host has been imported
for this laboratory yet — no verified performance conclusion is
available, and the development numbers below are not a substitute.

## Operation definitions

- **Mailbox and sequence-flag datasets**: one benchmark operation is one
  full run of the protocol — spawn the writer/reader thread(s), run the
  handshake (or, for the sequence flag, all `SEQFLAG_UPDATE_COUNT`
  sequential updates) to completion, join both threads. This is a macro
  measurement of the whole protocol including thread spawn/join, not a
  steady-state per-message cost, because the datasets are inherently
  one-shot (mailbox) or fixed-length (sequence flag) rather than an
  unbounded steady-state stream.
- **Counter-update dataset**: one benchmark operation is one full run of
  `COUNTER_THREAD_COUNT` threads each performing
  `COUNTER_INCREMENTS_PER_THREAD` increments, from thread spawn through
  join.
- Both languages use the identical definition above — persistent-worker
  semantics were not applicable here (unlike, say, the
  [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab's steady-state stream),
  so this lab does not have the Java-JMH-persistent-thread-vs-Rust-
  Criterion-spawn-per-sample mismatch that lab documents — both languages
  pay real thread spawn/join cost on every measured operation here, by
  the nature of the protocols themselves.
- Setup (fixture constant selection) happens outside the timed region in
  both languages; dataset generation is not applicable (these protocols
  operate on fixed, compile-time-constant payloads, not generated
  datasets).

## Method

Every required variant/dataset combination that is safe to benchmark for
throughput is wired in both languages (see java.md/rust.md); the
deliberately-broken variants (`plainBrokenPublication`/
`plain_broken_publication`, and the counter dataset's
`volatileSeqCstPublication`/`volatile_seq_cst_publication`) are
**excluded** from the shared throughput benchmark class, per this lab's
own guardrail against benchmarking an intentionally racy variant without
isolation — their behavior is characterized in the correctness suites and
exercises.md instead, via observed (not asserted) rates, never a
throughput number that could be mistaken for a validated result.

## Illustrative development data (this run — not a portable claim)

**Java (JMH, `Mode.AverageTime`, µs/op, lower is better; smoke-run
parameters, wiring check only):**

| Benchmark | Time |
|---|---|
| `mailbox_acquireRelease` | 96.895 µs/op |
| `mailbox_volatileSeqCst` | 96.115 µs/op |
| `mailbox_casLoop` | 96.841 µs/op |
| `mailbox_fenceBased` | 93.121 µs/op |
| `seqFlag_acquireRelease` | 104.713 µs/op |
| `seqFlag_volatileSeqCst` | 103.196 µs/op |
| `seqFlag_casLoop` | 168.975 µs/op |
| `seqFlag_fenceBased` | 111.052 µs/op |
| `counter_acquireRelease` | 7,347.787 µs/op |
| `counter_casLoop` | 12,069.097 µs/op |
| `counter_fenceBased` | 23,752.102 µs/op |

**Rust (Criterion `--quick`, median of a reduced sample count):**

| Benchmark | Time |
|---|---|
| `mailbox/acquire_release` | 32.826 µs |
| `mailbox/volatile_seq_cst` | 32.297 µs |
| `mailbox/cas_loop` | 33.817 µs |
| `mailbox/fence_based` | 32.505 µs |
| `sequence_flag/acquire_release` | 35.254 µs |
| `sequence_flag/volatile_seq_cst` | 32.085 µs |
| `sequence_flag/cas_loop` | 76.452 µs |
| `sequence_flag/fence_based` | 36.720 µs |
| `counter_update/acquire_release` | 5.331 ms |
| `counter_update/cas_loop` | 9.280 ms |
| `counter_update/fence_based` | 10.889 ms |

## What this shows (and does not)

**These two tables are not a Java-vs-Rust comparison.** JMH's smoke-run
parameters (0 warmup, 1×200ms measurement) and Criterion's `--quick` mode
(reduced sampling) were both chosen for wiring-check speed, not
statistical validity — neither run has the iteration count or JIT
warm-up needed to draw any conclusion, language-vs-language or
variant-vs-variant. They exist only to confirm every benchmark method
actually executes and returns a plausible order of magnitude before this
lab reaches the dedicated native-Linux host.

**The counter-update dataset's rough proportions are directionally
consistent with the theory** — `fenceBased`/`fence_based` (mutual
exclusion via a spinlock, protecting every single increment) costs more
than `casLoop`/`cas_loop` (a lock-free retry), which in turn costs more
than `acquireRelease`/`acquire_release` (a single genuine RMW
instruction with no retry) — but this ordering is exactly what the
mechanism predicts, so smoke-run numbers landing in the predicted order
is a wiring sanity check, not evidence of the *magnitude* of the effect.
A real conclusion about magnitude requires the native-Linux evidence run.

**Neither table is a "real-world" figure for any of these protocols** —
they measure this lab's specific fixture sizes
(`MAILBOX_TRIALS`/`SEQFLAG_UPDATE_COUNT`/`COUNTER_INCREMENTS_PER_THREAD`),
not a workload-representative operation count.

## Raw data and reproduction

```sh
# Correctness gate — run before trusting any timing:
node scripts/benchmark-platform/run-correctness-gate.js memory-ordering-atomics

# Smoke run (wiring check only — zero statistical value):
cd content/labs/memory-ordering-atomics/code/java && mvn -q -DskipTests package && \
  java -jar target/benchmarks.jar -f 1 -wi 0 -i 1 -r 200ms -w 200ms
cd content/labs/memory-ordering-atomics/code/rust && cargo bench -- --quick --noplot
```

The runnable projects are committed at `code/java/` (Maven + JMH) and
`code/rust/` (Cargo + Criterion) next to this file. JMH emits per-iteration
raw samples (`-rf json`); Criterion writes `target/criterion/**/new/raw.csv`
and a generated HTML report. Re-run on your own hardware — thread
scheduling, core topology, and JIT warm-up all change this curve.
