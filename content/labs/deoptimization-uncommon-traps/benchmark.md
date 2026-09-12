# Deoptimization and uncommon traps — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every dataset's input generator, every shift
  point/exception index/null stride (fixed constants, never randomized),
  and the correctness oracle: all five variants sum to the identical,
  fixture-pinned total for a given dataset
  (`code/fixtures/deoptimization-uncommon-traps-fixtures.json`).
- **Measured** — p99/p999, deoptimization count, recompilation count, the
  full latency timeline, and code-cache events, for each of the 15
  (variant × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| every (variant, dataset) cell | one pass over 1,000,000 elements in fixed 100-element timed batches, applying the variant's speculative-assumption pattern | the final accumulated total matches the fixture-pinned value for that (variant, dataset) pair |

This lab's variants are `kind=aux` in the runner configuration — JMH's
steady-state averaging cannot represent a one-time regime shift mid-run,
so every cell runs `DeoptTimelineHarness` directly (java.md), the same
category of tool content/labs/clocks-latency-histograms uses for
distribution mechanics a throughput mode would distort. Dataset
generation and the correctness check both happen inside the harness
before any timeline percentile is computed. Java is the measured side for
this lab's publication numbers; the Rust track runs the identical
variants through `deopt_timeline` as a separately disclosed instrument
(rust.md) — expected, not merely permitted, to show a flat timeline
throughout.

## Required metrics

p99/p999 (from the pre-shift and post-shift windows, reported
separately — never merged into one aggregate that would average the
spike away), deoptimization count and recompilation count (from HotSpot
diagnostic logs / JFR, capability-detected — some require a debug or
diagnostics-unlocked JVM build), the full latency timeline (the batched
nanosecond array itself, preserved as raw evidence, not just its
percentiles), and code-cache events (`-XX:+PrintCodeCache` /
JFR `jdk.CodeCacheStatistics`, capability-detected). Smoke runs are
wiring checks only and are never publication-eligible.

## Profiler evidence

`DeoptTimelineHarness`'s own batched timeline is the primary, always-
available evidence. HotSpot diagnostic flags
(`-XX:+PrintCompilation`, `-XX:+TraceDeoptimization`,
`-XX:+PrintCodeCache`) and JFR deoptimization/compilation events
supplement it with the compiler's own record of what happened, where the
host and JVM build support them. Where a tool is unavailable it is
recorded as unavailable, never substituted
(`docs/measurement-environments.md`).

## A real finding from development wiring (dev-only, never published)

Running the Java timeline harness on this repository's development
machine produced a real, reproducible spike for every variant expected
to produce one: `profileShiftAfterWarmup` (49,042 ns in the shift
window, vs. 625 ns for the `stableTypeProfile` control in the identical
window), `lateSubtypeLoading` (233,375 ns — the largest single-batch cost
measured, consistent with real class-loading overhead layered on top of
any deopt), and `rareExceptionPath` (1,826,834 ns, appearing in the
`postShift` window because its trigger index, 700,000, is deliberately
placed past the harness's `shiftWindow` boundary — see java.md). Running
the identical variants through Rust's `deopt_timeline` showed every
variant flat in the 100–300 ns range, including the two Java variants
that spiked dramatically. This is the mechanism this lab exists to teach,
directly confirmed in both directions on one machine — and it is exactly
why this lab's canonical evidence must still come from the reference
host: these specific numbers are development-machine noise-adjacent
illustrations, not a substitute for real, reproduced measurement.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/deoptimization-uncommon-traps/code/java && mvn test
cd content/labs/deoptimization-uncommon-traps/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -cp target/benchmarks.jar pl.kzybala.lab.deoptimization.DeoptTimelineHarness \
  --variant profileShiftAfterWarmup --dataset strategyDispatch
cargo run --release --bin deopt_timeline -- \
  --variant profileShiftAfterWarmup --dataset strategyDispatch

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh deoptimization-uncommon-traps \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh deoptimization-uncommon-traps \
  --profile smoke --cpus <CPU_A> --variant subtype-strategy
```

Raw timeline JSON (including every batch's nanosecond reading), perf
stat CSVs, placement evidence and environment metadata are produced per
variant by the runner and imported through the canonical result pipeline
— numbers are never transcribed into this page by hand.

## Limitations

- Whether, and how strongly, a given assumption violation triggers a
  visible spike is a property of the measured JDK build's specific
  inlining/deopt heuristics, not a portable guarantee; every conclusion
  here is scoped to the disclosed toolchain version.
- "using artificial class loading without disclosure" is a named trap:
  `lateSubtypeLoading`'s mechanism (constructing a never-before-touched
  class mid-run) is stated explicitly here and in java.md, never
  presented as an incidental detail.
- "hiding warm-up phase transitions" and "publishing only steady-state
  mean" are named traps this lab's entire methodology exists to prevent
  — no result on this page is ever reduced to a single steady-state
  average without the pre-shift/shift-window/post-shift breakdown.
- "equating Rust branch misprediction with JVM deoptimization" is a named
  trap: Rust's flat timeline is never described as "Rust also
  deoptimizes, just less" — it structurally does not deoptimize at all
  (rust.md).
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); the cross-language contrast here is about the
  *presence or absence of a mechanism*, not a speed comparison.
