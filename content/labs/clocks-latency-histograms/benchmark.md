# Clocks, latency histograms and percentiles — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only.

## What is measured vs what is deterministic

This lab deliberately splits its claims in two:

- **Deterministic distribution mechanics** — synthetic streams, recording
  modes, coordinated-omission correction, service-vs-response separation
  and per-thread merging are all fixture-exact
  (`code/fixtures/clocks-latency-histograms-fixtures.json`): both language
  suites must reproduce every value bit-for-bit before any timing is
  trusted. These tables never change per host and are never presented as
  measurements.
- **Host measurements** — clock cost/granularity (calibration harnesses),
  recording cost on the hot path (JMH/Criterion), and recording wall time
  inside the replay harnesses. Only these await native-Linux evidence.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| `baselineOp` | one xorshift64 step over benchmark state | shared xorshift64 fixture semantics |
| `timestampEveryOp` | baseline op + 2× `nanoTime`/`Instant::now` + one allocation-free record | recorded count == invocations |
| `sampledTimestamp` | baseline op; every 64th also timestamped+recorded | recorded count == invocations/64 |
| `recordOnly` | baseline op + record of a fixed value (clock excluded) | recorded count == invocations |
| calibration harness | 10⁶ back-to-back clock calls + 10⁵ consecutive deltas | zero monotonicity violations |
| replay harnesses | record the full deterministic dataset(s) | `fixtureExact: true` (else exit 1) |

Timer overhead is always a *difference of measured variants*
(`timestampEveryOp − baselineOp`, `recordOnly − baselineOp`), never a
free-standing claim. Java (JMH) and Rust (Criterion) are separate
instruments with disclosed settings — no cross-harness ranking is
published, and JMH's fork model has no Criterion counterpart.

## Required metrics

Timer overhead (derived as above), p50/p95/p99/p99.9/max (from the
harness histograms and the deterministic fixtures), and histogram
footprint (`getEstimatedFootprintInBytes` / implementation equivalent,
reported per histogram configuration). Smoke runs carry `scoreError: NaN`
by construction and are never publication-eligible.

## The percentile-convention trap (documented instrument difference)

On the pause dataset the naive histogram's p99.9 falls exactly on the
stall cliff — 100 stalls in 100 000 samples is precisely 0.1%. The Java
HdrHistogram resolves the tie below the cliff (59 999 ns); the Rust port
resolves it on the cliff (5 001 215 ns). Both are defensible conventions;
the lesson is that **a percentile at a distribution cliff is an
ill-conditioned statistic** — reporting it without its neighborhood
(p99, max, the cliff's mass) invites a 84× misreading depending on the
library. The shared fixtures pin every off-boundary value identically in
both languages, pin this one cell per-implementation, and exclude it from
cross-language comparison.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/clocks-latency-histograms/code/java && mvn test
cd content/labs/clocks-latency-histograms/code/rust && cargo test

# Deterministic replays + calibration (dev wiring checks):
mvn -q -DskipTests package
java -cp target/benchmarks.jar pl.kzybala.lab.clockslatency.CoReplayHarness --dataset pause
java -cp target/benchmarks.jar pl.kzybala.lab.clockslatency.MergeHarness
cargo run --release --bin co_replay -- --dataset burst

# Recording-cost smoke (wiring only — zero statistical value):
java -jar target/benchmarks.jar 'RecordingCostBenchmark' -f 1 -wi 1 -w 1s -i 2 -r 1s -foe true
cargo bench --bench recording_cost

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh clocks-latency-histograms \
  --profile publication --cpus <CPU_A>,<CPU_B>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh clocks-latency-histograms \
  --profile smoke --cpus <CPU_A>,<CPU_B> --variant timestamp-every-fixed
```

Raw JMH JSON, harness JSON, perf stat CSVs, placement evidence and
environment metadata are produced per variant by the runner and imported
through the canonical result pipeline — numbers are never transcribed
into this page by hand.

## Limitations

- Clock properties are per-host and per-kernel (clocksource, vDSO); the
  calibration numbers travel with the run's environment metadata only.
- The deterministic datasets model distribution *mechanics*; they are not
  workload simulations and carry no claim about any real system's shape.
- Percentiles at distribution cliffs are ill-conditioned (see above);
  conclusions cite the surrounding quantiles, never a cliff cell alone.
