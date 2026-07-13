# False sharing — Java/JMH project

Companion code for the [False sharing](https://kzybala.pl/lab/false-sharing/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

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

Requires JDK 21+. Uses JMH 1.37 (see `pom.xml`).
