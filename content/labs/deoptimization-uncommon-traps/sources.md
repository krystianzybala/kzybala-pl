# Deoptimization and uncommon traps — sources

- Aleksey Shipilëv, *"JVM Anatomy Quark #1: Deoptimization and
  Recompilation"* and related JVM Anatomy Quarks on uncommon traps —
  the primary reference for this lab's mechanism section:
  https://shipilev.net/jvm/anatomy-quarks/
- Cliff Click, *"A Brief and Incomplete History of JIT Compilation"* and
  HotSpot design documentation on speculative optimization and
  deoptimization (OpenJDK HotSpot Group).
- OpenJDK HotSpot diagnostic flags — `-XX:+PrintCompilation`,
  `-XX:+TraceDeoptimization`, `-XX:+PrintCodeCache` (debug/diagnostics-
  unlocked builds only):
  `java -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal -version | grep -i deopt`.
- JDK Flight Recorder (JFR) compilation/deoptimization events
  (`jdk.Deoptimization`, `jdk.Compilation`):
  https://docs.oracle.com/en/java/javase/22/docs/api/jdk.jfr/jdk/jfr/consumer/package-summary.html
- JITWatch — visualizing HotSpot compilation and deoptimization logs:
  https://github.com/AdoptOpenJDK/jitwatch
- Gil Tene, *"Understanding Latency"* talks — the case for percentile-
  and timeline-based reporting over steady-state means, directly
  motivating this lab's methodology (shared lineage with
  content/labs/clocks-latency-histograms' coordinated-omission material).
- The Rust Reference, *"Trait objects"* and LLVM's *"Inline Cost
  Analysis"* — the compile-time-fixed dispatch decisions that make
  runtime deoptimization structurally absent from Rust, this lab's
  central Rust-track finding: https://llvm.org/docs/InlineAdvisor.html
- `perf-stat(1)` — instructions/cycles/context-switches counter
  definitions: https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/clocks-latency-histograms` (the
  aux-harness-for-distribution-mechanics convention this lab's
  `DeoptTimelineHarness`/`deopt_timeline` reuse).
