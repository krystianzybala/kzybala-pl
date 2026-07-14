# CAS contention and backoff — Java project

Companion code for the [CAS contention and backoff](https://kzybala.pl/lab/cas-contention/)
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

Runs five benchmarks (`casIncrement1Thread`, `casIncrement2Threads`,
`casIncrement4Threads`, `casIncrement8Threads`, `singleWriterIncrement`) at
~15 s each by default (5 warmup + 10 measurement iterations of 1 s). Add
`-rf json -rff results.json` for raw per-iteration samples.

Requires JDK 21+. Uses JMH 1.37 (see `pom.xml`).
