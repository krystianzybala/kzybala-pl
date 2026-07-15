# False sharing — Java/JMH project

Companion code for the [False sharing](https://kzybala.pl/lab/false-sharing/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

## Test (correctness gate — run before trusting any benchmark number)

```sh
mvn test
```

Runs `CounterCorrectnessTest` (every variant — shared, padded, `@Contended`,
sharded — must produce exact counts under concurrent writers, asserted
against the shared fixture `../fixtures/false-sharing-fixtures.json`; the
Rust tests assert the same cases), `CounterLayoutTest` (real field offsets
verified with JOL — layout is never assumed) and
`EvidenceBenchmarkContractTest` (the structural contract the native-Linux
evidence runner depends on).

## Publication evidence (native Linux only)

Hardware-counter evidence (`perf stat`, `perf c2c`) is collected only by
`scripts/performance-lab/run-linux-evidence.sh` on a supported native
Linux host — see `docs/linux-evidence-runner.md`. macOS runs of this
project are development/smoke only.

## Build

```sh
mvn --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED package
```

(`--add-exports` on the Maven launcher itself is only needed if your Maven
version forks the compiler in-process; the compiler plugin is already
configured with the equivalent `compilerArgs` in `pom.xml`, so a plain `mvn
package` works on most setups. Add it to the command above only if you hit
`package jdk.internal.vm.annotation is not visible`.)

## Run

```sh
java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -jar target/benchmarks.jar
```

Add `-rf json -rff results.json` to get raw per-iteration samples instead of
just the summary table.

Requires JDK 21+. Uses JMH 1.37 (see `pom.xml`). `mvn` enforces this floor at build time (`maven-enforcer-plugin`, plab-002) — an older JDK fails fast with a clear error instead of a confusing compile failure.
