# Cache locality and working-set size — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every variant's payload (`values[i] = i`), the
  traversal-order generators (Fisher-Yates permutation, Sattolo single-
  cycle, blocked/tiled index sequence), and the correctness oracle:
  every variant, on a given element count, sums to the identical
  `n·(n−1)/2` (`code/fixtures/cache-locality-working-set-fixtures.json`).
- **Measured** — ns/element, L1 misses, LLC misses, cache references,
  bandwidth and IPC, for each of the 20 (variant × workingSet) cells,
  only from the native-Linux evidence runner, against working-set sizes
  resolved from the DETECTED cache topology.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| `sequential` | one pass, index `0..n-1` in natural order | sum equals `n·(n−1)/2` |
| `random` | one pass following a Fisher-Yates permutation of `0..n` | sum equals `n·(n−1)/2` |
| `pointerChase` | one dependent pass following a Sattolo single-cycle `next[]` for exactly `n` steps | sum equals `n·(n−1)/2`; the cycle must return to index 0 after exactly `n` steps and visit every index exactly once |
| `blocked` | one tiled column-major pass over a `side × side` matrix (`side = floor(sqrt(n))`, `blockSize = 32`) | sum equals `side²·(side²−1)/2` |

Dataset element counts are resolved from `CacheTopology`/topology
detection in `@Setup(Level.Trial)`, never in the measured method; the
`blocked` variant's actual element count (`side²`) is documented as
slightly below the nominal working-set element count by construction
(rounding to a perfect square) — never silently equated with the other
three variants' exact count for the same `workingSet` name. Java (JMH) is
the measured side for this lab's publication numbers; the Rust track runs
the identical operations through `cargo bench` / `cache_locality_evidence`
as a separately disclosed instrument (rust.md) — no cross-harness ranking
is published.

## Required metrics

ns/element, L1 misses, LLC misses, cache references, bandwidth (derived
from bytes touched / elapsed time) and IPC — from `perf stat` wrapping
the pinned worker process
(`scripts/performance-lab/labs/cache-locality-working-set.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler evidence

`perf stat` (required, LLC/dTLB/branch counters), `perf c2c` where
available (not required for this single-worker lab, but capability-
detected consistently with every other lab), and async-profiler's cache-
miss profile mode where the host supports it. Where a tool is
unavailable it is recorded as unavailable, never substituted
(`docs/measurement-environments.md`).

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/cache-locality-working-set/code/java && mvn test
cd content/labs/cache-locality-working-set/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar 'CacheLocalityBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true
cargo bench --bench cache_locality

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh cache-locality-working-set \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh cache-locality-working-set \
  --profile smoke --cpus <CPU_A> --variant chase-llc
```

Raw JMH JSON, perf stat CSVs, cache-topology manifests, placement
evidence and environment metadata are produced per variant by the runner
and imported through the canonical result pipeline — numbers are never
transcribed into this page by hand.

## Limitations

- Cache and TLB sizes are detected from `/sys/devices/system/cpu/cpu0/cache`
  on the measuring host at run time; the byte thresholds behind
  `l1`/`l2`/`llc`/`2xllc`/`large` therefore vary by host and travel with
  each run's environment metadata rather than being fixed constants.
- Page faults during measurement and NUMA migration are named traps
  specifically because either can dominate a result with latency that has
  nothing to do with the cache mechanism under test; datasets are fully
  pre-touched in setup and workers are pinned before timing to exclude
  both, and the runner's placement-verification gate (java.md, rust.md)
  fails the run if a worker migrated mid-measurement.
- Comparing element counts across working sets with different byte
  footprints per element (this lab's own `blocked` vs. the other three
  variants, or any future extension) is exactly the "comparing element
  counts with different byte footprints" trap this lab teaches — every
  comparison in this lab's conclusions is scoped to a single `workingSet`
  name, never across differently-sized datasets.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy).
