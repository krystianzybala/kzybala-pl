# Benchmark harness traps — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as results.

## Operation definitions (the contract)

One benchmark operation is one full kernel pass over the selected dataset,
with setup, dataset generation and validation outside the timed region —
except in the `setupInsideTimed`/`setup_inside_timed` variants, where the
placement of setup *is* the experiment:

| Dataset | One operation | Oracle (shared fixture) |
|---|---|---|
| `scalar` | 1000 xorshift64 steps from seed 42 | mixed value `2260733264014075113` |
| `reduction` | wrapping sum of a 4096-element xorshift64 array (seed 42) | sum `6622022393378204083` |
| `parser` | parse + checksum of the prebuilt 1760-char CSV input (seed 7, 256 values) | checksum `1274698891203359752` |
| `counter` | reset + 10000 LCG advances from seed 0 (reset is part of the operation) | final state `206428032307178832` |

Java and Rust implement these with identical 64-bit wrapping semantics;
the shared fixture (`code/fixtures/benchmark-harness-traps-fixtures.json`)
is hard-coded in both suites, and every measurement shape — trap and
corrected alike — must produce the oracle value. Intentional differences
between the instruments (JMH forks have no Criterion equivalent; Criterion
samples closures, JMH samples timed iterations) are documented here and in
the language tracks; **cross-harness numbers are separate instruments,
never one ranking** — this lab publishes no Java-versus-Rust winner by
design.

## Variant matrix

Trap/corrected pairs of the identical kernel (harness is the only
variable):

| Variant axis | Trap | Corrected | Measured on |
|---|---|---|---|
| input provability | `foldedInput` | `runtimeInput` | scalar |
| result sink | (discarded result — not benchmarked; measures nothing) `returnedResult` | `consumedResult` | reduction |
| setup placement | `setupInsideTimed` | `setupOutside` | parser |
| state handling | leak (proved by test, not benchmarked) | per-invocation reset | counter |
| process isolation | single fork (`-f 1`) | isolated forks (profile count) | scalar |

The full 4×4 dataset sweep is available through the commands below; the
publication core is the defect-revealing cell per axis listed above.

## Required metrics

ns/op and ops/s (JMH `AverageTime` in ns; Criterion estimates), confidence
intervals (JMH 99.9% CI; Criterion bootstrap CI), variance (per-fork /
per-sample raw data), and allocation/op (JMH `-prof gc`,
`gc.alloc.rate.norm`). All imported through the canonical result schema
with units and uncertainty; smoke runs (single fork, minimal iterations)
carry `scoreError: NaN` by construction and are never publication-eligible.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/benchmark-harness-traps/code/java && mvn test
cd content/labs/benchmark-harness-traps/code/rust && cargo test

# Development smoke (wiring check only — zero statistical value):
cd content/labs/benchmark-harness-traps/code/java && mvn -q -DskipTests package
java -jar target/benchmarks.jar 'HarnessTrapsBenchmark' \
  -p dataset=scalar -f 1 -wi 1 -w 1s -i 2 -r 1s -foe true
cd ../rust && cargo bench --bench harness_traps

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh benchmark-harness-traps \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh benchmark-harness-traps \
  --profile smoke --cpus <CPU_A> --variant folded-scalar
```

Raw JMH JSON, perf stat CSVs, per-fork placement evidence and environment
metadata are produced per variant by the runner and imported through the
canonical result pipeline — numbers are never transcribed into this page
by hand. Each imported run records toolchain versions, source revision,
CPU topology, governor state and the exact command line.

## Limitations

- The folding contrast is compiler-version-sensitive by nature: a newer
  JIT may fold more or less. The claim under test is the *existence* of a
  trap/corrected gap, not its exact magnitude.
- The process-isolation contrast exists only on the Java side (Criterion
  has no fork model) — an instrument asymmetry, not a language property.
- Single-core pinned measurements; no concurrency effects are in scope.
