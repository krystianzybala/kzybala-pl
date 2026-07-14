# Cache hierarchy — Java/JMH project

Companion code for the [Cache hierarchy](https://kzybala.pl/lab/cache-hierarchy/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

## Build

```sh
mvn package
```

## Run

```sh
java -jar target/benchmarks.jar
```

Add `-rf json -rff results.json` to get raw per-iteration samples instead of
just the summary table. Runs four benchmarks (`sequentialSmall`,
`randomSmall`, `sequentialLarge`, `randomLarge`) at ~15 s each by default
(5 warmup + 10 measurement iterations of 1 s) — expect several minutes total.

Requires JDK 21+. Uses JMH 1.37 (see `pom.xml`). `mvn` enforces this floor at build time (`maven-enforcer-plugin`, plab-002) — an older JDK fails fast with a clear error instead of a confusing compile failure.
