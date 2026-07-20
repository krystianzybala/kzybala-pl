# Clocks, latency histograms and percentiles — Java track

Package `pl.kzybala.lab.clockslatency`: deterministic synthetic streams
(`SyntheticLatency`, `ResponseTimeModel`), the lab's histogram conventions
(`LatencyHistograms` over org.hdrhistogram), one JMH benchmark and three
dedicated harnesses — JMH measures the *instrument's cost*; the
distribution mechanics run in harnesses because aggregate throughput modes
would misrepresent them.

## The pieces

- **`System.nanoTime()` calibration** — `CalibrationHarness` measures the
  three properties you must know about your clock: per-call cost
  (amortized over a million back-to-back calls), observed granularity
  (smallest non-zero delta), and monotonicity (violations exit non-zero).
- **HdrHistogram integration** — `LatencyHistograms.newHistogram()` pins
  the lab's range (1 ns–60 s) and precision (3 significant digits),
  auto-resize off: recording is allocation-free after construction
  (`-prof gc` shows zero alloc/op on the recording benchmarks).
- **Recording cost on the hot path** — `RecordingCostBenchmark`: the bare
  fixed-cost op, timestamp-every-op (two `nanoTime` calls + record),
  sampled instrumentation (every 64th), and record-only (clock excluded).
  Timer overhead is the *difference* between variants, never a guess.
- **Coordinated omission** — `CoReplayHarness --dataset pause` replays
  the deterministic stall dataset through naive and
  `recordValueWithExpectedInterval` recording; `--dataset burst` replays
  the FIFO burst model separating service from response time. The
  distribution outputs are fixture-exact or the harness exits non-zero.
- **Per-thread recording** — `MergeHarness`: two pinned workers record
  independent histograms with zero hot-path coordination; the merged
  result must match the shared fixture exactly (bucket addition is
  lossless).

## Build, correctness gate, run

```bash
cd content/labs/clocks-latency-histograms/code/java

# correctness gate — sequences, percentiles, CO correction, merge
mvn test

# build
mvn -q -DskipTests package

# calibrate THIS machine's nanoTime (wiring check on a dev box)
java -cp target/benchmarks.jar pl.kzybala.lab.clockslatency.CalibrationHarness

# deterministic CO replay (fixtureExact must print true)
java -cp target/benchmarks.jar pl.kzybala.lab.clockslatency.CoReplayHarness --dataset pause
java -cp target/benchmarks.jar pl.kzybala.lab.clockslatency.CoReplayHarness --dataset burst

# per-thread record + merge
java -cp target/benchmarks.jar pl.kzybala.lab.clockslatency.MergeHarness

# recording-cost smoke (wiring only — zero statistical value)
java -jar target/benchmarks.jar 'RecordingCostBenchmark' -f 1 -wi 1 -w 1s -i 2 -r 1s -foe true

# allocation per op for the recording path (must be ~zero)
java -jar target/benchmarks.jar 'RecordingCostBenchmark.recordOnly' -f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc
```

Publication-grade numbers come only from the native-Linux evidence runner
(benchmark.md); the commands above validate wiring and correctness.

## Reading the results

The derived quantities are the point:

- `timestampEveryOp − baselineOp` ≈ two clock calls + one record — the
  full per-op price of observation;
- `recordOnly − baselineOp` ≈ the histogram record alone (typically a
  fraction of the clock cost);
- `sampledTimestamp` sits near the baseline — sampling buys the hot path
  back, and the sampled *fixture* shows what it costs the tail estimate.
