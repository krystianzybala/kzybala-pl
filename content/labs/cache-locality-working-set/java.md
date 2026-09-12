# Cache locality and working-set size — Java track

Package `pl.kzybala.lab.cachelocality`: deterministic traversal-order
generation shared with the correctness fixture (`CacheLocalityFixtures`),
topology detection (`CacheTopology`, the same convention as the reference
cache-hierarchy lab), and dev/publication JMH benchmarks.

## The pieces

- **Traversal orders, not data generation** — every dataset's payload is
  `values[i] = i`; `CacheLocalityFixtures.randomPermutation` (Fisher-
  Yates) and `sattoloNext` (a single N-cycle, same algorithm as
  content/labs/cache-hierarchy's `ChaseTables.randomCycle`) generate the
  ORDER elements are visited in. This sidesteps the "different dataset
  generation between languages" trap for the payload entirely — there is
  no payload to generate, only an order, and that order's generator is
  the part pinned by the shared fixture.
- **`sumBlocked`** — a column-major pass over a `side × side` row-major
  matrix, decomposed into `blockSize × blockSize` tiles processed one at
  a time: block-columns outer, block-rows next, a small row-major scan
  inside each tile.
- **`CacheTopology`** — detects L1d/L2/LLC sizes from
  `/sys/devices/system/cpu/cpu0/cache` (Linux) with a documented,
  explicitly flagged fallback elsewhere; `workingSetElements(name)` binds
  a named dataset (`l1`/`l2`/`llc`/`2xllc`/`large`) to the DETECTED
  topology, never a hardcoded byte count.
- **`CacheLocalityBenchmark`** (dev/wiring) — the four variants over a
  fixed 1,000,000-element dataset, unpinned.
- **`CacheLocalityLinuxEvidenceBenchmark`** (publication) — the full 4×5
  variant×working-set matrix, one pinned worker, the `n·(n−1)/2`
  correctness oracle re-checked in `@Setup` before any timing, and the
  resolved topology written to `cache-locality-topology-<pid>.json`.

## Build, correctness gate, run

```bash
cd content/labs/cache-locality-working-set/code/java

# correctness gate — every traversal order sums to n*(n-1)/2
mvn test

# build
mvn -q -DskipTests package

# dev smoke — all four variants, fixed 1,000,000-element dataset
java -jar target/benchmarks.jar 'CacheLocalityBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true

# one cell of the publication matrix, unpinned dev run
java -jar target/benchmarks.jar 'CacheLocalityLinuxEvidenceBenchmark' \
  -p variant=pointerChase -p workingSet=llc -f 1 -wi 2 -w 1s -i 3 -r 1s -foe true
```

Publication-grade throughput numbers and cache-miss counters come only
from the native-Linux evidence runner (benchmark.md); the commands above
validate wiring and correctness on a development machine, whose
`CacheTopology` may report `detected: false` (a documented fallback, not
this host's real cache sizes).

## Reading the results

- Compare `random` against `pointerChase` on the **same working set**
  first — both defeat the prefetcher identically, so the gap between
  them isolates memory-level parallelism specifically (theory.md).
- Compare `sequential` against `blocked` on the **largest** working sets
  — the theory's claim is that blocking recovers most of sequential's
  advantage; a `blocked` result far from `sequential` and close to
  `random` is worth investigating (block size too large for the actual
  L1/L2 capacity is the first suspect).
- Track any single variant across all five working sets — this is where
  the "staircase" from theory.md's Visualization 2 either shows up in the
  real counters or doesn't; a smooth ramp instead of steps at the L2/LLC
  crossovers is itself a finding worth explaining, not ignoring.
