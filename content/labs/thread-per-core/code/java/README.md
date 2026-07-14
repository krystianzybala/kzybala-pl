# Thread-per-core architecture — Java project

Companion code for the [Thread-per-Core Architecture](https://kzybala.pl/lab/thread-per-core/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

## Test

```sh
mvn test
```

Confirms `PartitionedCounter` is correct on its one owning thread, and
`SharedCounterPool` is correct under concurrent increments across
partitions from multiple threads.

## Build and run the benchmark

```sh
mvn package
java -jar target/benchmarks.jar
```

Runs `sharedPoolIncrement` (4 threads contending for one lock, each
incrementing a randomly chosen partition) and `ownedPartitionIncrement` (4
threads, each incrementing its own unshared counter) — 5 warmup + 10
measurement iterations of 1 s each by default. Add `-rf json -rff results.json`
for raw per-iteration samples.

Requires JDK 21+. Uses JMH 1.37 (see `pom.xml`). `mvn` enforces this floor at build time (`maven-enforcer-plugin`, plab-002) — an older JDK fails fast with a clear error instead of a confusing compile failure.
