# Cache coherence and MESI — Java project

Companion code for the [Cache coherence and MESI](https://kzybala.pl/lab/mesi/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

This is **not a benchmark project** — it has no JMH dependency. It's two
small classes (a shared-writer counter, a single-owner counter) plus
correctness tests, meant to be run under Linux `perf c2c` yourself if you
want to observe real coherence traffic. See "Diagnostic methodology" in the
lab's `theory.md` for the `perf c2c` walkthrough.

## Test

```sh
mvn test
```

Confirms both examples behave correctly under the concurrency pattern each
is meant to illustrate: `SharedWriterExample` under two concurrent writer
threads, `SingleOwnerExample` on its one owning thread.

Requires JDK 21+. `mvn` enforces this floor at build time (`maven-enforcer-plugin`, plab-002) — an older JDK fails fast with a clear error instead of a confusing compile failure.
