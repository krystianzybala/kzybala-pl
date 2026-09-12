# GC algorithms and tail latency — sources

- Charlie Hunt & Binu John, *Java Performance* — the GC algorithms
  chapter's treatment of live-set-driven collection cost, a primary
  reference for this lab's mechanism section.
- Gil Tene, *"Understanding Java Garbage Collection"* talks — the
  percentile-over-mean discipline this lab follows directly, shared
  lineage with content/labs/clocks-latency-histograms.
- OpenJDK, *G1 Garbage Collector* documentation — region-based,
  incremental, pause-target-driven collection, the strategy behind this
  lab's default collector flag:
  https://docs.oracle.com/en/java/javase/22/gctuning/garbage-first-g1-garbage-collector1.html
- OpenJDK, *Parallel Collector* documentation — stop-the-world,
  generational, throughput-oriented collection, the strategy behind this
  lab's `collectorMatrix` comparison flag:
  https://docs.oracle.com/en/java/javase/22/gctuning/parallel-collector1.html
- JDK Flight Recorder (JFR) GC events (`jdk.GarbageCollection`,
  `jdk.GCPhasePause`, `jdk.GCHeapSummary`) — the primary evidence API
  this lab's `GcTailHarness` uses directly, field names confirmed by
  live probing against the measured JDK build:
  https://docs.oracle.com/en/java/javase/22/docs/api/jdk.jfr/jdk/jfr/consumer/package-summary.html
- OpenJDK, unified logging `-Xlog:gc` — see `java -Xlog:help` on the
  measured JDK build for exact tag availability and default verbosity.
- The Rust standard library, `Box`/`Drop` documentation — the
  synchronous, deterministic reclamation this lab's Rust track measures
  directly, structurally distinct from a collector:
  https://doc.rust-lang.org/std/boxed/struct.Box.html,
  https://doc.rust-lang.org/std/ops/trait.Drop.html
- `perf-stat(1)` — context-switches/cycles/page-faults counter
  definitions relevant to allocation and collection overhead:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`,
  `docs/evidence-maturity.md`. Reference implementation:
  `content/labs/safepoints-ttsp` (the JFR-episode-correlation pattern
  this lab's `GcTailHarness` follows) and
  `content/labs/clocks-latency-histograms` (the percentile reporting
  discipline this lab's evidence follows).
