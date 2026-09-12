# Array of Structures vs Structure of Arrays — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — record generation (byte-identical between Java and
  Rust), every layout's byte stride (`recordStrideBytes`, computed from
  the declared `MemoryLayout`/`#[repr(C)]` struct, not measured), and the
  layout-invariance oracle: every layout of a dataset must reproduce the
  identical hot-field sum and cold checksum (`code/fixtures/aos-vs-soa-fixtures.json`).
- **Measured** — ns/record, bytes/record (from real cache-miss and
  LLC-load counters, not the declared stride alone), cache misses,
  allocations, and vectorization evidence, for each of the 12
  (layout × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| every (layout, dataset) cell | one pass over 1,000,000 records, accumulating the wrapping sum of `(hotA[i] + hotB[i])` via the layout's own hot-field access pattern | hot sum matches the shared fixture; every non-`aosHeap`... every layout's sum matches every other layout's sum on the identical dataset |

Dataset generation, layout construction and the correctness oracle all
run in `@Setup(Level.Trial)`, never in the measured method; cold fields
are never touched by the timed operation — that is the literal meaning of
"read-mostly scan over selected fields" this lab measures. Java (JMH) is
the measured side for this lab's publication numbers; the Rust track runs
the identical operation through `cargo bench` / `aos_soa_evidence` as a
separately disclosed instrument (rust.md) — no cross-harness ranking is
published.

## Required metrics

ns/record, bytes/record (declared stride **and** measured cache traffic),
cache misses, allocations (`-prof gc` for the Java heap-object variant;
zero expected for the array-backed layouts after setup), and
vectorization evidence — from `perf stat` wrapping the pinned worker
process (`scripts/performance-lab/labs/aos-vs-soa.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler and layout evidence

- **JOL** (`JolReport`, java.md) — real per-instance and per-graph heap
  layout for the AoS heap-objects variant; runs on any JVM, captured
  independently of the native-Linux host.
- **FFM layout inspection** — the Java `AosPackedLayout`'s
  `recordStrideBytes()`/`totalBytes()` and the Rust
  `AosPackedLayout::<COLD>::record_stride_bytes()` report the *declared*
  byte layout directly from the struct/`MemoryLayout` definition.
- **`perf stat`** — cache-references, cache-misses, LLC-loads,
  LLC-load-misses, dTLB-load-misses (the real, measured density evidence
  the tables above are checked against).
- **`-prof perfasm` / `cargo asm`** — capture whether a given layout's hot
  loop is actually vectorized once run on the native-Linux host; where a
  tool is unavailable it is recorded as unavailable, never substituted
  (`docs/measurement-environments.md`).

## The instance-vs-offset VarHandle trap (documented implementation detail, not a language difference)

During development, an earlier `AosPackedLayout` used `MemoryLayout`-path
`VarHandle`s stored on a non-constant (instance) field and measured
roughly **60× slower** than the offset-based rewrite that ships in this
lab — see java.md for the exact before/after numbers. This is a real,
reproducible JIT-optimization pitfall specific to how `VarHandle`s are
obtained and stored, not a property of off-heap memory access in
general — which is exactly why "measuring construction instead of scan"
and its cousins are named traps rather than abstract warnings: an
unrelated implementation accident can dominate a benchmark number that
looks, superficially, like a clean layout comparison.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/aos-vs-soa/code/java && mvn test
cd content/labs/aos-vs-soa/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar 'AosVsSoaBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true
java -cp target/benchmarks.jar pl.kzybala.lab.aosvssoa.JolReport
cargo bench --bench aos_vs_soa

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh aos-vs-soa \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh aos-vs-soa \
  --profile smoke --cpus <CPU_A> --variant soa-positions
```

Raw JMH JSON, perf stat CSVs, placement evidence and environment metadata
are produced per variant by the runner and imported through the canonical
result pipeline — numbers are never transcribed into this page by hand.

## Limitations

- Java object header size and compressed-oops behavior are JVM-build- and
  architecture-specific; JOL's exact byte counts (java.md) travel with the
  run's environment metadata and are never assumed to generalize to a
  different JDK build.
- This lab's operation reads exactly two hot fields together by design;
  the hybrid-vs-SoA comparison's outcome is conditional on that access
  pattern (theory.md, "throughput by accessed-field ratio") and is never
  generalized to "hybrid always beats SoA."
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); the `aosHeap` variant in particular measures a
  *different* mechanism per language (Java: object header + GC tracing;
  Rust: extra allocation, no header) under the same variant name — see
  rust.md's cross-language parity notes.
