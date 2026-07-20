# Clocks, latency histograms and percentiles — exercises

## Exercise 1 (diagnosis): the load generator that lies

A load test tool sends a request, waits for the response, then sends the
next request. Against a service that stalls completely for 2 seconds once
per minute, it reports:

```text
requests/min: 5900   mean: 9.8ms   p99: 12ms   p999: 2000ms   max: 2004ms
```

The team concludes: "worst case ~2 s, but 99.9% of users are fine."
Diagnose why this conclusion is wrong, name the phenomenon, and state
what the corrected p99 would qualitatively look like.

**Success criteria:** you name coordinated omission; you explain that the
generator sent *fewer* requests during the stall (each 2 s stall swallows
~200 would-be requests that were never sent, so the histogram is missing
exactly the worst samples); you predict that corrected recording pushes
the stall's weight into p99 (hundreds of milliseconds to seconds), not
just p99.9; and you point at this lab's pause-dataset replay as the
executable demonstration.

<details>
<summary>Hint</summary>

Count the samples: at ~100 req/s, one minute yields ~5900 completed
requests — where did the other ~200 go, and what latency *would* they
have experienced? The lab's fixture shows naive p99 = 56 µs vs corrected
p99 = 4.9 ms on exactly this failure shape.
</details>

## Exercise 2 (implementation): calibrate before you claim

Using `CalibrationHarness` (Java) and the `calibration` bin (Rust) on
your own machine, then modify `RecordingCostBenchmark` locally to add a
variant `doubleTimestamp()` that calls `System.nanoTime()` four times
(two pairs) per operation and record it alongside `timestampEveryOp`.

**Success criteria:** the measured gap between `doubleTimestamp` and
`timestampEveryOp` is within ~30% of 2× your calibrated per-call clock
cost (the instrument predicts its own overhead); you state your machine's
observed granularity and per-call cost with the run's settings; and you
delete the variant afterwards.

<details>
<summary>Hint</summary>

If the gap is much smaller than predicted, check whether the JIT merged
or hoisted the extra calls — `nanoTime` is intrinsic but not free of
scheduling; keep the calls data-dependent on the op result to prevent
reordering (e.g. mix the previous timestamp into the recorded value).
</details>

<details>
<summary>Solution</summary>

Per-call cost × extra calls is additive on the hot path when the calls
cannot be eliminated: two more `nanoTime` invocations add ≈2× the
calibrated per-call cost to the per-op time. If your delta matches the
calibration, your instrument model is consistent; if not, the benchmark
(inlining, reordering, power management) — not the arithmetic — needs
investigating. This is the discipline: never claim a per-op cost smaller
than your instrument's own calibrated resolution.
</details>

## Exercise 3 (evidence interpretation): read the replay output

Below is the shape of the lab's own `CoReplayHarness --dataset burst`
output (deterministic content):

```json
{
  "serviceTime":  {"count":100000,"p50":1009, "p99":55903, "max":59999},
  "responseTime": {"count":100000,"p50":21023,"p99":223615,"max":377599},
  "recordingWallNanosFor200kValues": 1834211,
  "fixtureExact": true
}
```

Answer from the output alone: (a) why do the two histograms disagree by
20× at p50 when they describe the same 100 000 operations; (b) which of
the four numeric blocks is a *measurement* of the machine that produced
this JSON, and which are reproducible constants; (c) a colleague quotes
"~9 ns per recorded value" from `recordingWallNanos/200000` — name two
reasons that figure is weaker evidence than the JMH `recordOnly` variant.

**Success criteria:** (a) service time excludes queueing — bursts of 10
arrivals share one server, so later arrivals in each burst wait; (b) only
`recordingWallNanos` is a host measurement, the histograms are
deterministic fixtures (`fixtureExact` proves it); (c) the wall-time
figure has one sample and no warm-up/fork discipline, and it amortizes
sequence generation + two different recording modes into one number,
while the JMH variant isolates a single record per op under a controlled
harness with uncertainty reported.

<details>
<summary>Hint</summary>

Ask of every number: *what question does it answer, and how many samples
support it?* One wall-clock difference answers "how long did this loop
take, once, here" — nothing more.
</details>
