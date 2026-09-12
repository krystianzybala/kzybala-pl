# GC algorithms and tail latency — Java track

Package `pl.kzybala.lab.gctail`: `GcTailFixtures` (deterministic input
generation), `GcTailOperations` (the five allocation-pattern variants),
`Node` (the shared allocation unit) and `GcTailHarness` — the real
evidence tool for this lab.

## The pieces

- **`GcTailFixtures`** — `N = 1,000,000`; three datasets
  (`objectGraphChurn`, `messagePipeline`, `retainedCache`), each an
  xorshift64-derived (or, for `objectGraphChurn`, linear) `long[]` with a
  fixture-pinned checksum. Every variant reads the identical stream for a
  given dataset.
- **`Node`** — `long value` plus a small `long[] extra` (size 8/16/4
  depending on dataset) — the payload every allocating variant creates,
  sized so escape analysis cannot plausibly scalar-replace it away in the
  variants that are supposed to allocate.
- **`GcTailOperations`** — the five variants, all summing the identical
  checksum:
  - `lowAllocationReuse` — one `Node`, mutated in place every iteration.
    Zero allocation after warmup; this variant's own evidence is the
    control showing zero GC episodes.
  - `steadyHighAllocation` — a fresh `Node` every iteration, discarded
    immediately after its value is read.
  - `burstyAllocation` — 100-iteration quiet blocks (reused, like
    `lowAllocationReuse`) alternating with 20-iteration bursts (fresh
    `Node`s with an 8× larger `extra` array), repeating for the full run.
  - `growingLiveSet` — a fresh `Node` every iteration, retained forever in
    a growing `List`; live set climbs monotonically to `N` nodes by the
    end of the run.
  - `collectorMatrix` — IDENTICAL code to `steadyHighAllocation`; only the
    launching JVM's collector flag differs (benchmark.md) — a runner-level
    distinction, not a code distinction.
- **`GcTailHarness` — the real evidence tool.** It starts a JFR
  `Recording` enabling `jdk.GarbageCollection` and `jdk.GCHeapSummary`,
  runs the requested (variant, dataset), verifies the checksum against
  `GcTailFixtures`, stops the recording, then reads every
  `jdk.GarbageCollection` event's own `duration` as one collection
  episode's pause and every `jdk.GCHeapSummary` event with
  `when="After GC"` for `heapUsed` (the live set immediately after that
  collection). This is measured directly from the JVM's own record of
  what happened — never estimated from a wall-clock delta around the
  workload call.

## A real finding from development wiring (dev-only, never published)

Running `GcTailHarness` on this repository's development machine (all
runs `-Xms512m -Xmx512m` except `growingLiveSet`, which used `-Xmx512m`
to give its climbing live set headroom) produced results directly
consistent with this lab's mechanism. `lowAllocationReuse` on
`objectGraphChurn` triggered **zero** GC episodes — the direct evidence
that reuse genuinely avoids the collector entirely, not just in theory.
`steadyHighAllocation` on `messagePipeline` triggered 5 young-generation
collections with p50 ≈ 0.95 ms and p99 ≈ 1.24 ms — small, frequent,
cheap, matching a small stable live set. `growingLiveSet` on
`retainedCache` triggered 5 collections (a mix of `G1New` and `G1Old`)
with p50 ≈ 4.9 ms and p99/max ≈ 15.8 ms — visibly larger than
`steadyHighAllocation`'s, directly consistent with a climbing live set
raising collection cost, exactly this lab's hypothesis and not a
collector-name effect (both runs used G1). `collectorMatrix` on
`objectGraphChurn` showed `G1New` (p50 ≈ 1.09 ms, p99 ≈ 1.12 ms) against
`ParallelScavenge` (p50 ≈ 1.26 ms, p99 ≈ 1.47 ms) on the byte-for-byte
identical code and allocation pattern — a real, attributable
collector-strategy difference, not a workload difference, since none
exists between the two runs. None of these exact numbers are published
evidence; the directions are what benchmark.md's real evidence is
checked against.

## Build, correctness gate, run

```bash
cd content/labs/gc-tail-latency/code/java

# correctness gate — every variant sums to the identical total per dataset
mvn test

# build
mvn -q -DskipTests package

# THE evidence tool — run directly, any variant/dataset, any collector flag
java -Xms512m -Xmx512m -XX:+UseG1GC -cp target/benchmarks.jar \
  pl.kzybala.lab.gctail.GcTailHarness --variant growingLiveSet --dataset retainedCache

# collectorMatrix differs ONLY by this launch flag — identical Java code,
# a real JVM-level strategy difference
java -Xms512m -Xmx512m -XX:+UseParallelGC -cp target/benchmarks.jar \
  pl.kzybala.lab.gctail.GcTailHarness --variant collectorMatrix --dataset objectGraphChurn
```

There is no JMH benchmark in this lab — JMH's throughput/avgtime modes
have no way to represent a percentile-pause distribution across a whole
run, the same reasoning safepoints-ttsp documents for its own harness;
`GcTailHarness` is the tool for both dev wiring and (via the
native-Linux runner) publication evidence (benchmark.md).

## Reading the results

- Always compare `growingLiveSet` against `steadyHighAllocation` on the
  **same dataset and collector** first — this isolates the live-set
  effect from anything else, since both variants allocate at a
  comparable rate.
- Compare `collectorMatrix`'s two collector runs on the **same
  dataset** — this is the only pair in this lab where the code and
  allocation pattern are byte-for-byte identical, so any difference is
  attributable to the collector strategy alone.
- `lowAllocationReuse`'s `gcCount: 0` is itself a result, not a missing
  measurement — it is the direct evidence that reuse eliminates GC
  pressure, the control every other variant's pause numbers should be
  read against.
- Read `liveSetBytes` alongside the pause percentiles, not instead of
  them — a variant with a small live set but a large p999 points at
  workload phase (bursty timing) rather than live-set size as the driver.
