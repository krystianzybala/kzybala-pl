# Escape analysis and scalar replacement — sources

- Aleksey Shipilëv, *"JVM Anatomy Quark #18: Scalar Replacement in Depth"*
  and *"Escape Analysis and Weak References"* — the primary reference for
  this lab's mechanism section: https://shipilev.net/jvm/anatomy-quarks/
- Cliff Click, John Rose et al., original HotSpot escape-analysis and
  scalar-replacement design notes (OpenJDK HotSpot Group archives) — the
  NoEscape/ArgEscape/GlobalEscape classification this lab's theory page
  uses.
- OpenJDK HotSpot diagnostic flags —
  `-XX:+PrintEscapeAnalysis`, `-XX:+PrintEliminateAllocations`,
  `-XX:+PrintEliminateLocks` (debug/diagnostics-unlocked builds only):
  `java -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal -version | grep -i escape`.
- JMH `-prof gc` documentation (`org.openjdk.jmh.profile.GCProfiler`) —
  the primary evidence tool this lab relies on:
  https://github.com/openjdk/jmh
- JDK Flight Recorder (JFR) allocation-profiling events
  (`jdk.ObjectAllocationSample`, `jdk.ObjectAllocationInNewTLAB`):
  https://docs.oracle.com/en/java/javase/22/docs/api/jdk.jfr/jdk/jfr/consumer/package-summary.html
- The Rust Reference, *"Data Layout"* and the `Box<T>` documentation — the
  explicit-heap-allocation semantics this lab's `sum_boxed` exercises:
  https://doc.rust-lang.org/std/boxed/struct.Box.html
- `perf-stat(1)` — page-faults/context-switches counter definitions
  relevant to allocator/GC behavior:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/inlining-call-site-shape` (the
  `DONT_INLINE`/`#[inline(never)]` optimization-barrier convention this
  lab reuses).
