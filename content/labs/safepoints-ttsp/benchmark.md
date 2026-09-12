# Safepoints and time to safepoint — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every dataset's worker partitioning of the
  identical logical range and the correctness oracle: every partitioning
  sums to the same fixture-pinned total
  (`code/fixtures/safepoints-ttsp-fixtures.json`).
- **Measured** — TTSP, safepoint operation time, application stopped
  time, p99 latency and thread count, for each of the 15
  (variant × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One "episode" | Oracle |
|---|---|---|
| every (variant, dataset) cell | one safepoint, correlated across `jdk.SafepointBegin`/`jdk.ExecuteVMOperation`/`jdk.SafepointEnd` by `safepointId` | the worker pool's total (checksum) matches the fixture-pinned value regardless of how many episodes occurred |

Every variant is `kind=aux` in the runner configuration — JMH's
throughput/avgtime modes cannot represent a genuinely multi-threaded,
JVM-coordinated stop-the-world event — so every cell runs
`SafepointHarness` directly (java.md). `longLoopSparsePolls` is the
IDENTICAL Java code as `cooperativeLoop`, launched with
`-XX:-UseCountedLoopSafepoints`; the runner supplies this flag per
variant (`scripts/performance-lab/labs/safepoints-ttsp.conf`). Java is
the measured side for this lab's publication numbers; the Rust track
builds an explicit coordinator and runs it separately (rust.md) — no
cross-harness ranking is published, and its numbers are never read as
"Rust's version of the same measurement."

## Required metrics

TTSP (`jdk.SafepointBegin`'s own `duration`), safepoint operation time
(`jdk.ExecuteVMOperation`'s `duration` where `safepoint=true`),
application stopped time (TTSP + operation + `jdk.SafepointEnd`'s
`duration`), p99 latency (computed across every captured episode in a
run) and thread count (`jdk.SafepointBegin`'s `totalThreadCount` field)
— captured directly by `SafepointHarness`, supplemented by `perf stat`
wrapping the pinned worker process
(`scripts/performance-lab/labs/safepoints-ttsp.conf`, `LAB_PERF_EVENTS`).
Smoke runs are wiring checks only and are never publication-eligible.

## Profiler evidence

`SafepointHarness`'s own JFR-derived episode report is the primary,
always-available evidence — it reads the JVM's own record of what
happened, never a wall-clock estimate. `-Xlog:safepoint,gc+stop-time`
(capability-detected; flag availability and default-on status vary by
JDK build) and async-profiler's wall-clock mode supplement it where the
host supports them. Where a tool is unavailable it is recorded as
unavailable, never substituted (`docs/measurement-environments.md`).

## A real finding from development wiring (dev-only, never published)

Running the Java harness on this repository's development machine
showed `threadFleet`'s TTSP roughly 9× `numericLoop`'s under the
identical `cooperativeLoop` variant (more threads, more coordination);
`manyIdleThreads` showed a p99/max nearly 50× its own control's, a real
per-thread handshake tax across 300 sleeping threads; and
`threadInNativeCall`'s TTSP was, if anything, slightly *below* baseline
— direct confirmation that a native-blocked thread does not add to
TTSP. Running the Rust coordinator across the identical matrix showed
every combination terminate correctly, with
`longLoopSparsePolls`/`nativeSleepDowncall` producing a genuine,
dramatic ≈408 ms time-to-pause — proof that Rust can reproduce a large
real pause once coordination has to wait on something, directly against
this lab's "claiming Rust has no pauses" trap. None of these exact
numbers are published evidence; the directions and mechanisms are what
this lab's real, reproduced evidence is checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/safepoints-ttsp/code/java && mvn test
cd content/labs/safepoints-ttsp/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -cp target/benchmarks.jar pl.kzybala.lab.safepoints.SafepointHarness \
  --variant manyIdleThreads --dataset threadFleet
cargo run --release --bin safepoint_coordinator -- \
  --variant longLoopSparsePolls --dataset nativeSleepDowncall

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh safepoints-ttsp \
  --profile publication --cpus <CPU_A>,<CPU_B>,<CPU_C>,<CPU_D>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh safepoints-ttsp \
  --profile smoke --cpus <CPU_A>,<CPU_B>,<CPU_C>,<CPU_D> --variant idle-fleet
```

Raw JFR recordings, the correlated episode report, perf stat CSVs,
placement evidence and environment metadata are produced per variant by
the runner and imported through the canonical result pipeline — numbers
are never transcribed into this page by hand.

## Limitations

- "blaming GC for all pauses" is a named trap this lab's whole
  methodology exists to prevent: every reported pause is decomposed into
  TTSP, operation time and resume, so a small operation time inside a
  large stopped-time never gets attributed to "the GC was slow."
- "using obsolete flags without version checks" is a named trap:
  `-XX:-UseCountedLoopSafepoints` and `-Xlog:safepoint` availability and
  default behavior are disclosed per the actual measured JDK build, never
  assumed to carry across versions.
- "claiming Rust has no pauses" is a named trap directly addressed by
  `longLoopSparsePolls`/`nativeSleepDowncall`'s real, measured Rust
  pause (rust.md) — the absence of an *automatic* mechanism is this
  lab's Rust-track finding, never the absence of the phenomenon.
- "running with uncontrolled OS scheduling" is a named trap: publication
  runs are pinned to an explicit CPU set (`core_quad`) and the runner's
  standard host-stability gates apply identically to this lab.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); the two languages' harnesses measure
  structurally different mechanisms (automatic vs. explicitly built
  coordination), never merged into one ranking.
