# SPSC ring buffer — Java project

Companion code for the [SPSC Ring Buffer](https://kzybala.pl/lab/spsc-ring-buffer/)
Performance Lab. Not part of this site's own build or CI — a standalone
Maven project you clone/copy out and run on your own machine.

## Test

```sh
mvn test
```

Confirms `SpscRingBuffer` rejects reservations when full, reports empty
before anything is published, delivers values in FIFO order across
wrap-around, and is correct with a real producer thread and a real
consumer thread.

## Build and run the benchmark

```sh
mvn package
java -jar target/benchmarks.jar
```

Runs `SpscRingBufferBenchmark`, which pins one producer thread and one
consumer thread to the same 1024-slot buffer using JMH's `@Group` pattern,
measuring steady-state pipeline throughput (5 warmup + 10 measurement
iterations of 1 s by default). Add `-rf json -rff results.json` for raw
per-iteration samples.

Requires JDK 21+. Uses JMH 1.37 (see `pom.xml`). `mvn` enforces this floor at build time (`maven-enforcer-plugin`, plab-002) — an older JDK fails fast with a clear error instead of a confusing compile failure.
