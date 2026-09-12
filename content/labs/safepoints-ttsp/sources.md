# Safepoints and time to safepoint — sources

- Nitsan Wakart, *"Are you biased? (Safepoints and biased locking)"* and
  related posts on safepoint mechanics and time-to-safepoint — a primary
  reference for this lab's mechanism section:
  https://psy-lob-saw.blogspot.com/
- Gil Tene, *"Understanding Java Garbage Collection"* talks — the
  TTSP-vs-operation-time decomposition this lab measures directly, shared
  lineage with content/labs/clocks-latency-histograms.
- OpenJDK HotSpot, `-Xlog:safepoint` / `-Xlog:gc+stop-time` unified
  logging — see `java -Xlog:help` on the measured JDK build for exact
  tag availability.
- JDK Flight Recorder (JFR) safepoint events
  (`jdk.SafepointBegin`, `jdk.SafepointStateSynchronization`,
  `jdk.SafepointEnd`, `jdk.ExecuteVMOperation`) — the primary evidence
  API this lab's `SafepointHarness` uses directly:
  https://docs.oracle.com/en/java/javase/22/docs/api/jdk.jfr/jdk/jfr/consumer/package-summary.html
- OpenJDK JEP 312, *Thread-Local Handshakes* — the mechanism that lets
  many (though not all) VM operations avoid a full safepoint, relevant
  context for why not every stop-the-world event behaves identically:
  https://openjdk.org/jeps/312
- `usleep(3)` / POSIX native-call semantics — the FFM downcall this
  lab's `NativeSleep`/`threadInNativeCall` variant uses to put a thread
  genuinely outside JVM-managed execution.
- The Rust standard library, `std::sync::atomic` documentation — the
  primitives this lab's `safepoint_coordinator` uses to build explicit
  stop-the-world coordination: https://doc.rust-lang.org/std/sync/atomic/
- `perf-stat(1)` — context-switches/cycles counter definitions relevant
  to multi-threaded coordination overhead:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/clocks-latency-histograms` (the
  percentile/raw-timeline reporting discipline this lab's evidence
  follows).
