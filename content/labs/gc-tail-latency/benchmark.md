# GC algorithms and tail latency — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every variant's checksum over a given dataset's
  identical value stream: allocation pattern and collector choice must
  never change the result
  (`code/fixtures/gc-tail-latency-fixtures.json`).
- **Measured** — allocation rate, pause p50/p99/p999, throughput, CPU
  overhead, RSS and live set, for each of the 18
  (variant × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One "episode" | Oracle |
|---|---|---|
| every (variant, dataset) cell | one garbage collection, read from `jdk.GarbageCollection`'s own `duration`, correlated with `jdk.GCHeapSummary` by `gcId` | the variant's checksum matches the fixture-pinned value regardless of how many collections occurred |

Every variant is `kind=aux` in the runner configuration — JMH's
throughput/avgtime modes cannot represent a percentile-pause distribution
across a whole run — so every cell runs `GcTailHarness` directly
(java.md). `colg1`/`colpar` (the `collectorMatrix` variant) are the
IDENTICAL Java code, launched with `-XX:+UseG1GC` and
`-XX:+UseParallelGC` respectively; the runner supplies this flag per
variant (`scripts/performance-lab/labs/gc-tail-latency.conf`). Every
variant runs with the identical fixed heap (`-Xms512m -Xmx512m`) —
deliberately not tiny, so no result is attributable to an artificially
starved heap rather than the variant under test. Java is the measured
side for this lab's publication numbers; the Rust track measures
allocator latency and one synchronous reclamation event instead of a GC
pause distribution (rust.md) — no cross-harness ranking is published,
and its numbers are never read as "Rust's version of the same
measurement."

## Required metrics

Allocation rate (derived from dataset size and elapsed wall-clock time),
pause p50/p99/p999 (`jdk.GarbageCollection.duration` across every
captured episode in a run), throughput (operations/sec over the full
workload), CPU overhead and RSS (`perf stat` wrapping the pinned worker
process) and live set (`jdk.GCHeapSummary.heapUsed` where
`when="After GC"`) — captured directly by `GcTailHarness`, supplemented
by `perf stat` (`scripts/performance-lab/labs/gc-tail-latency.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler evidence

`GcTailHarness`'s own JFR-derived episode report is the primary,
always-available evidence — it reads the JVM's own record of what
happened, never a wall-clock estimate. `-Xlog:gc` (capability-detected;
flag availability and default verbosity vary by JDK build) supplements
it where the host supports enabling it. `async-profiler`'s allocation
mode and `heaptrack` are recorded as unavailable rather than substituted
where the host does not support them (`docs/measurement-environments.md`).

## A real finding from development wiring (dev-only, never published)

Running the Java harness on this repository's development machine showed
`lowAllocationReuse` triggering zero GC episodes (the direct control),
`growingLiveSet` on `retainedCache` showing p99 pauses (≈15.8 ms) roughly
an order of magnitude larger than `steadyHighAllocation`'s (≈1.24 ms) on
a comparable dataset despite both allocating at a similar rate — directly
consistent with live-set size, not allocation rate alone, driving pause
cost — and `collectorMatrix` showing a real, attributable difference
between `G1New` (p50 ≈ 1.09 ms) and `ParallelScavenge` (p50 ≈ 1.26 ms) on
byte-for-byte identical code. Running the Rust evidence binary across the
same variants showed allocation latency itself flat and tiny (p50 ≈
41 ns across every allocating variant) with `growingLiveSet`'s single
reclamation event costing ≈28.4 ms to drop one million retained nodes —
a real cost, comparable in magnitude to Java's GC pauses for the same
variant, but structurally a single synchronous event rather than a
recurring background pause. None of these exact numbers are published
evidence; the directions and mechanisms are what this lab's real,
reproduced evidence is checked against.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/gc-tail-latency/code/java && mvn test
cd content/labs/gc-tail-latency/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -Xms512m -Xmx512m -XX:+UseG1GC -cp target/benchmarks.jar \
  pl.kzybala.lab.gctail.GcTailHarness --variant growingLiveSet --dataset retainedCache
cargo run --release --bin gc_tail_harness -- \
  --variant growingLiveSet --dataset retainedCache

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh gc-tail-latency \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh gc-tail-latency \
  --profile smoke --cpus <CPU_A> --variant growing-cache
```

Raw JFR recordings, the correlated episode report, perf stat CSVs,
placement evidence and environment metadata are produced per variant by
the runner and imported through the canonical result pipeline — numbers
are never transcribed into this page by hand.

## Limitations

- "tiny heaps chosen to manufacture pauses" is a named trap this lab's
  whole methodology exists to prevent: every variant runs with the
  identical, deliberately generous fixed heap, so no reported pause is
  attributable to heap starvation.
- "different semantic workloads" is a named trap: every variant reads
  the identical value stream for a given dataset and produces the
  identical checksum — only allocation pattern (and, for
  `collectorMatrix`, the launching collector flag) differs.
- "calling Rust allocation-free by default" is a named trap directly
  addressed by `gc_tail_harness`'s own measured, non-zero allocation
  latency and `growingLiveSet`'s real ≈28.4 ms reclamation event
  (rust.md) — the absence of an *automatic collector* is this lab's
  Rust-track finding, never the absence of any cost.
- "comparing throughput-only to latency collectors" is a named trap:
  `collectorMatrix` reports pause percentiles for both collector flags,
  never throughput alone, so a collector that wins on mean throughput but
  loses on p999 is visible as such, not hidden behind an average.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); the two languages' harnesses measure
  structurally different mechanisms (GC pause distribution vs. allocator
  latency plus one synchronous reclamation event), never merged into one
  ranking.
