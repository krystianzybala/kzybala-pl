# Branch prediction and data distribution — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every dataset (byte flags, integer thresholds,
  mixed hot/cold records), every arrangement (biased9010, random5050,
  sorted) and the filtered sum each produces are fixed by the shared
  fixture (`code/fixtures/branch-prediction-fixtures.json`) and reproduced
  bit-exactly by both language suites before any timing is trusted. The
  equality of `random5050`'s, `sorted`'s and `branchless`'s filtered sums
  is itself part of that gate — sorting and going branchless must never
  change the answer, only the time.
- **Measured** — ns/element, `branches`, `branch-misses`, IPC (derived
  from `cycles`/`instructions`) and cycles/element, for each of the 12
  (variant × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| byteFlags / intThresholds cell | one pass over 1,000,000 elements, accumulating the wrapping filtered sum via the variant's technique | filtered sum matches the fixture (biased9010) or matches `random5050`'s sum (sorted, branchless) |
| mixedHotCold cell | one pass over 1,000,000 records, accumulating payload where kind is hot | same oracle, on `(kind, payload)` pairs |

Dataset generation, sorting and the correctness oracle all run in
`@Setup(Level.Trial)`, never in the measured method; the returned sum is
consumed as the benchmark result so it cannot be eliminated as dead code.
Java (JMH) is the measured side for this lab's publication numbers; the
Rust track runs the identical operation through `cargo bench` /
`branch_evidence` as a separately disclosed instrument (rust.md) — no
cross-harness ranking is published.

## Required metrics

ns/element, `branches`, `branch-misses`, IPC (`instructions`/`cycles`),
cycles/element — from `perf stat` wrapping the pinned worker process
(`scripts/performance-lab/labs/branch-prediction.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler and assembly evidence

`-prof perfasm` (JMH) and `cargo asm` (Rust) capture the actual compiled
branchy/branchless sequences for representative cells once run on the
native-Linux host; the theory page's assembly listing is a generic,
textbook illustration of the pattern and is explicitly not a substitute
for this captured evidence. Where a tool is unavailable on the evidence
host, the corresponding artifact is recorded as unavailable — never
substituted or fabricated (`docs/measurement-environments.md`).

## The 32-bit-lane trap (documented implementation detail, not a language difference)

The branchless mask (`(diff >>> 31) ^ 1` in Java, `((diff as u32) >> 31) ^
1` in Rust) is deliberately computed on a 32-bit lane in both languages,
matching the value domains (bytes 0–255, thresholds up to 999,999) that
both fit comfortably inside `i32`/`int`. Computing the same shift on the
64-bit accumulator type instead would silently change which bit the shift
inspects and break the mask — this is why the fixture pins a dedicated
test (`branchlessMaskNeverProducesAThirdValue` /
`branchless_mask_never_produces_a_third_value`) asserting the mask is
always exactly 0 or 1 across the threshold boundary, rather than trusting
the arithmetic by inspection.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/branch-prediction/code/java && mvn test
cd content/labs/branch-prediction/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar 'BranchPredictionBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true
cargo bench --bench branch_prediction

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh branch-prediction \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh branch-prediction \
  --profile smoke --cpus <CPU_A> --variant random-byte
```

Raw JMH JSON, perf stat CSVs, placement evidence and environment metadata
are produced per variant by the runner and imported through the canonical
result pipeline — numbers are never transcribed into this page by hand.

## Limitations

- Real hardware predictors combine per-branch and history-based state and
  a branch-target buffer; the theory page's single-counter model explains
  the qualitative shape of the result, not any specific predictor's
  internal state, and no claim here attributes a number to a specific
  microarchitectural structure without counter evidence.
- The branchless variant's win, if any, is conditional on the guaranteed
  per-element arithmetic cost being cheaper than the *expected*
  misprediction cost it replaces — this is data- and host-dependent and is
  never generalized beyond the measured cells.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); cross-language differences in this lab, if any,
  are implementation-technique differences on the same host, not a
  language verdict.
