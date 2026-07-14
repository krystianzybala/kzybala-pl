# Memory ordering in Java and Rust — Java project

Companion code for the [Memory ordering in Java and Rust](https://kzybala.pl/lab/memory-ordering/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

This is **not a benchmark project** — reordering effects are timing- and
hardware-dependent (see "Diagnostic methodology"/theory.md "Methodology and
limitations"). It's four small classes (plain vs. release/acquire
publication, an opaque-vs-volatile counter, a store-buffering litmus test)
plus correctness tests.

## Test

```sh
mvn test
```

Confirms the release/acquire fix and the volatile counter behave correctly.
The store-buffering test runs 500 iterations and prints how many times it
observed the classic anomaly on your machine/JVM — it does not fail the
build either way, since not reproducing it proves nothing (see the lab's
investigation task).

Requires JDK 21+. `mvn` enforces this floor at build time (`maven-enforcer-plugin`, plab-002) — an older JDK fails fast with a clear error instead of a confusing compile failure.
