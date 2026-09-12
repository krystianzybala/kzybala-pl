# Deoptimization and uncommon traps — Java track

Package `pl.kzybala.lab.deoptimization`: three concrete `OperationStrategy`
types, the five speculative-assumption variants
(`DeoptOperations` for correctness, `DeoptTimelineHarness` for real
per-call latency evidence), and a dev/wiring JMH benchmark.

## The pieces

- **`TypeA`/`TypeB`/`TypeC`** — three distinct concrete implementations
  of `OperationStrategy`; `TypeC` is never constructed before
  `DeoptFixtures.SHIFT_POINT` in `lateSubtypeLoading`, exactly modeling a
  class that is loaded for the first time deep into a long-running
  process.
- **`DeoptFixtures`** — fixed, documented constants for every shift
  point: `SHIFT_POINT = 500,000`, `RARE_EXCEPTION_INDEX = 700,000`,
  `NULL_STRIDE = 1000`. Nothing here is randomized or time-based — every
  spike (or its absence) happens at the same reproducible index on every
  run.
- **`DeoptOperations`** — the five variants, computing totals only (the
  correctness-checked path); `sumRareExceptionPath` throws and catches a
  genuine `ArithmeticException` at exactly one index; `sumNullabilityShift`
  works over boxed `Long` values so a real `null` can appear.
- **`DeoptTimelineHarness`** — **the actual evidence tool for this lab.**
  JMH's steady-state averaging cannot represent a one-time regime shift
  in the middle of a run (this lab's "hiding warm-up phase transitions"
  and "publishing only steady-state mean" traps, benchmark.md) — the same
  reasoning content/labs/clocks-latency-histograms uses for its
  distribution-mechanics harnesses. It times the whole 1,000,000-element
  pass in fixed 100-element batches (never per-call — a single arithmetic
  op is far smaller than `System.nanoTime()`'s own cost and resolution)
  and reports pre-shift / shift-window / post-shift percentiles
  separately.

## A real finding from development wiring (dev-only, never published)

Running `DeoptTimelineHarness` on this repository's development machine
produced exactly the shape this lab's theory predicts: `stableTypeProfile`'s
shift-window max stayed at 625 ns (indistinguishable from its own
steady-state noise); `profileShiftAfterWarmup` spiked to **49,042 ns** in
the same window; `lateSubtypeLoading` spiked to **233,375 ns** — by far
the largest single-batch cost of any variant, consistent with class
loading/linking being genuinely expensive on top of any deopt itself;
`rareExceptionPath`'s spike (index 700,000) falls *outside* the
harness's `shiftWindow` label (which brackets `SHIFT_POINT = 500,000`) by
design — it shows up instead as a **1,826,834 ns** outlier in the
`postShift` window's own max, the single most expensive event across
every variant measured. None of these exact numbers are published
evidence; the *shape* — one dramatic, localized spike, then a return to
near-baseline — is what benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/deoptimization-uncommon-traps/code/java

# correctness gate — all five variants agree with the fixture-pinned totals
mvn test

# build
mvn -q -DskipTests package

# dev smoke — aggregate throughput only, wiring/correctness check
java -jar target/benchmarks.jar 'DeoptBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true

# THE evidence tool — the actual latency timeline, run directly
java -cp target/benchmarks.jar pl.kzybala.lab.deoptimization.DeoptTimelineHarness \
  --variant profileShiftAfterWarmup --dataset strategyDispatch

# HotSpot compilation/deopt logs (capability-detected; requires a debug
# or diagnostics-unlocked JVM build — see benchmark.md for what this
# host can and cannot produce)
java -XX:+UnlockDiagnosticVMOptions -XX:+TraceDeoptimization \
  -cp target/benchmarks.jar pl.kzybala.lab.deoptimization.DeoptTimelineHarness \
  --variant lateSubtypeLoading --dataset strategyDispatch 2>&1 | grep -i deopt
```

Publication-grade percentiles and deopt/recompilation counts come only
from the native-Linux evidence runner (benchmark.md); the batched
timeline itself is real, reproducible evidence on any JVM — only its
*magnitude* is restricted to the reference host.

## Reading the results

- Always compare a variant's `shiftWindow.maxNs` against its own
  `preShift.maxNs`, never against another variant's absolute number — the
  spike-to-baseline *ratio* is the mechanism signal; absolute nanosecond
  values are host- and JDK-build-specific.
- `rareExceptionPath`'s spike is expected to land in `postShift`, not
  `shiftWindow` — its trigger index (`RARE_EXCEPTION_INDEX = 700,000`) is
  deliberately placed past the harness's `shiftWindow` boundary
  (`SHIFT_POINT` + 10,000 calls); check the `postShift.maxNs`/`p999Ns`
  specifically for this variant, not `shiftWindow.maxNs`.
- If a variant that should spike shows a flat timeline instead, check
  `-XX:+PrintCompilation`/JFR before concluding the mechanism failed to
  reproduce — a sufficiently conservative JIT tier (or a build that
  never actually optimized the hot path in the first place) can mask the
  effect entirely, which is itself a finding worth reporting precisely.
