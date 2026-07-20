# Clocks, latency histograms and percentiles — theory

## Performance question and hypothesis

**Question:** what does a nanosecond timestamp actually mean, and how
should latency distributions be recorded without distorting the hot path?

**Hypothesis:** clock source, timestamp overhead, histogram resolution and
sampling strategy materially affect p99 and p99.9 conclusions — the
instrument shapes the answer as much as the system under test.

**What would disprove it:** if per-operation timestamping cost the same as
the bare operation, if sampled and exhaustive recording reported the same
tail percentiles with the same confidence, and if naive recording under
injected pauses reported the same p99.9 as coordinated-omission-corrected
recording, then the instrument would be neutral and this lab's premise
wrong. Each claim has a paired variant (bare vs instrumented op, full vs
sampled recording, naive vs corrected replay), so each is separately
falsifiable — and the distribution-level claims are checked against
*deterministic* fixtures both languages must reproduce bit-exactly.

## Learning objective

Interpret latency distributions instead of averages, separate service time
from response time, choose histogram precision deliberately, and know what
your clock can and cannot resolve.

## Prerequisites

- The [Benchmark harness traps](/lab/benchmark-harness-traps/) lab (DCE,
  warm-up and harness-settings discipline are assumed here).
- Ability to run `mvn` and `cargo bench`.

## Pre-lab diagnostic

A dashboard reports "average latency 2.1 ms" for a service. A user
complains that every tenth click takes over a second. Can both be true
simultaneously — and if so, what *single* statistic would have exposed the
problem the average hides?

(Answer at the end of this page.)

## The mechanism: the instrument is part of the experiment

- **What a timestamp is.** `System.nanoTime()` and `std::time::Instant`
  promise *monotonic elapsed* time within a process — not wall-clock time,
  not cross-machine time. On Linux both bottom out in
  `clock_gettime(CLOCK_MONOTONIC)`, usually backed by the TSC via the
  vDSO. Three separate properties matter and must be calibrated, never
  assumed: **cost** per call, **granularity** (smallest delta it ever
  reports), and **monotonicity** (specified, but worth verifying — a
  violation flags broken infrastructure).
- **Timestamp overhead distorts small measurements.** If a call pair costs
  tens of nanoseconds, timestamping a 4 ns operation reports mostly the
  clock. The lab's recording-cost benchmark measures the bare operation,
  the fully timestamped operation, and recording-only — so clock cost and
  histogram cost are separated by subtraction, not guessed.
- **Averages hide tails by construction.** A mean is one moment of the
  distribution; latency pain lives in the quantiles. An HdrHistogram-style
  log-bucketed histogram records every value allocation-free at bounded
  relative error (here: 3 significant digits over 1 ns–60 s) and answers
  any percentile after the fact.
- **Resolution vs footprint is a real trade-off** — the histogram's
  dynamic range and precision determine its memory footprint; the lab
  prints the actual footprint next to every percentile set so the cost of
  precision is visible, not folded away.
- **Service time is not response time.** Service time is how long the
  server worked; response time is how long the requester waited (queueing
  included). Under bursty arrivals the same operations produce wildly
  different distributions for the two questions — the lab's deterministic
  FIFO model makes that difference exact and reproducible.
- **Coordinated omission.** If the load generator waits for a slow
  response before sending the next request, every request that *would*
  have been delayed by the stall is silently never sent — the recorded
  distribution omits exactly the samples that hurt. The corrected
  recording backfills the missed arrivals at the expected interval
  (HdrHistogram's standard correction).
- **Sampling is a bargain with the tail.** Recording every 64th value
  keeps the median honest but thins the extreme tail to a handful of
  samples — the sampled fixture shows p99/p99.9 wobbling while p50 stays
  put.

## Visualization 1: histogram and percentile curve (deterministic)

The lab's bimodal fixture (100 000 deterministic values: 95% at
800–1199 ns, 5% at 40 000–59 999 ns), as recorded — both language suites
reproduce this table exactly:

| Statistic | Value (ns) |
|---|---|
| count | 100 000 |
| p50 | 1 009 |
| p95 | 1 194 |
| p99 | 55 903 |
| p99.9 | 59 711 |
| max | 59 999 |

The mean of this distribution is ≈3 400 ns — a value that is *nobody's
experience*: 95% of operations are three times faster, 5% are fifteen
times slower. The percentile column is the honest summary; the mean
answers a question no user asks.

## Visualization 2: coordinated omission, before and after (deterministic)

The same bimodal stream with a 5 ms stall injected into every 1000th
operation, recorded naively vs corrected (expected interval 1 µs):

| Statistic | Naive recording | CO-corrected |
|---|---|---|
| count | 100 000 | 837 236 |
| p50 | 1 009 ns | 819 199 ns |
| p95 | 1 194 ns | 4 587 519 ns |
| p99 | 56 223 ns | 4 923 391 ns |
| max | 5 062 655 ns | 5 062 655 ns |

Naive recording sees the stalls only as 100 slightly-slow samples and
reports a p99 of 56 µs; the corrected histogram accounts for every arrival
each stall blocked and reports a p99 of 4.9 **ms** — nearly two orders of
magnitude. Neither table is a measurement of a real system: both are
deterministic fixtures, which is exactly why the comparison is exact and
reproducible in both languages.

## Visualization 3: service time vs response time (deterministic)

The burst dataset (bursts of 10 arrivals every 50 µs, single FIFO server,
service times from the bimodal stream):

| Statistic | Service time | Response time |
|---|---|---|
| p50 | 1 009 ns | 21 023 ns |
| p95 | 1 194 ns | 140 927 ns |
| p99 | 55 903 ns | 223 615 ns |
| p99.9 | 59 711 ns | 316 159 ns |

Same operations, same server, different question. A benchmark that times
only the operation body answers the service-time column; the users queue
in the response-time column.

## Terminology

- **Monotonic clock** — never goes backward within a process; unrelated
  to wall-clock time.
- **Granularity / resolution** — smallest reportable timestamp delta, as
  observed (calibrated), not as documented.
- **Percentile / quantile** — the value below which the given fraction of
  samples falls; p99.9 = the best of the worst 0.1%.
- **HdrHistogram** — log-bucketed fixed-range histogram with bounded
  relative error and allocation-free recording.
- **Service time / response time** — server busy time vs requester wait
  (queueing included).
- **Coordinated omission (CO)** — a load generator whose sampling
  schedule is influenced by the system's own slowness, silently dropping
  the worst samples.

## Assumptions and scope

- Single process, monotonic in-process clocks; cross-machine clock
  synchronization (NTP/PTP) is out of scope.
- Distribution mechanics are taught on *deterministic synthetic* streams
  so both languages verify them bit-exactly; only instrument costs
  (clock calls, recording) are host measurements.
- One documented instrument difference exists by design: on the pause
  dataset, p99.9 falls exactly on the stall cliff and the Java and Rust
  HdrHistogram implementations resolve that tie differently — see
  benchmark.md ("the percentile-convention trap").

## Pre-lab diagnostic — answer

Yes — trivially. With 90% of clicks at ~1 ms and 10% at ~1.2 s, the mean
is ≈120 ms… and even that depends on the mix; a 2.1 ms average with a
1-in-10 second-long tail just needs a heavier fast mode. The statistic
that exposes it: any tail percentile at or beyond the complaint rate
(here p90/p99) — which is why this lab records distributions, not means.
