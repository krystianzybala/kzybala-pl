# GC algorithms and tail latency — theory

## Performance question and hypothesis

**Question:** how do allocation rate, live-set size and collector choice
shape latency tails?

**Hypothesis:** collector labels alone do not predict latency; live set,
allocation rate, heap sizing, barriers and workload phase determine p99
and p999 behavior.

**What would disprove it:** if switching the collector flag alone,
holding allocation pattern and heap fixed, produced the same pause shape
every time regardless of live set or allocation rate, the premise that
"the collector's name is the dominant variable" would be right and this
lab's hypothesis would be wrong. Every variant in this lab sums the
identical, deterministic checksum over the identical value stream —
allocation pattern and collector choice are the only things that change,
and this lab's real evidence (JFR-derived, never estimated) is checked
against that. **A collector's name is a starting point for a
conversation about latency, not the answer to it.**

## Learning objective

Select a collector from workload evidence rather than reputation, reason
about how live-set size and allocation rate independently shape pause
tails, and explain the throughput/latency trade-off a given collector
choice actually buys.

## Prerequisites

- The [Clocks, latency histograms and percentiles](/lab/clocks-latency-histograms/)
  lab (this lab reports p50/p99/p999, never a mean, for exactly the
  reasons that lab establishes).
- The [Escape analysis and scalar replacement](/lab/escape-analysis-scalar-replacement/)
  lab (allocation that the JIT cannot eliminate is this lab's raw
  material — every variant here is written so escape analysis cannot
  remove it).
- The [Safepoints and time to safepoint](/lab/safepoints-ttsp/) lab (a
  GC pause is, mechanically, a safepoint-coordinated VM operation; this
  lab measures the operation and its consequences, that lab measured the
  coordination cost of reaching one).

## Pre-lab diagnostic

Two services run the identical code and the identical G1 collector flag.
Service A reports a p99 pause of 3 ms. Service B, otherwise configured
identically, reports a p99 pause of 40 ms. A teammate concludes "G1 must
be misbehaving on service B's host." What question would you ask before
accepting that conclusion, given this lab's hypothesis?

(Answer at the end of this page.)

## The mechanism: three independent levers, one visible number

- **A GC pause is a safepoint-coordinated VM operation.** Before a
  collection can run, every relevant thread must reach a safepoint
  (safepoints-ttsp's subject); the collection itself is the operation
  that runs once they have. This lab's evidence — `jdk.GarbageCollection`'s
  `duration` — is that operation's own cost, already past the
  coordination phase.
- **Live-set size sets a floor on collection cost.** A collector that
  moves or scans live objects (a copying young-gen collector, G1's
  evacuation) pays roughly in proportion to how much data survives, not
  how much was allocated. This lab's `growingLiveSet` variant isolates
  this directly: identical allocation code to `steadyHighAllocation`,
  except nothing is ever discarded.
- **Allocation rate sets how often you pay, not how much each payment
  costs.** A high allocation rate with a small, stable live set (this
  lab's `steadyHighAllocation`) triggers young-generation collections
  frequently, but each one stays cheap because there is little to
  survive. Allocation rate and live-set size are independent dials, and
  conflating them is a common misdiagnosis this lab is built to correct.
- **Workload phase — bursty vs. steady — changes which percentile
  matters.** A collector tuned for steady, predictable allocation can
  show a very different p999 under `burstyAllocation`'s alternating
  quiet/burst pattern, because a burst can trigger a collection at a
  moment the collector's heuristics did not expect. Averaging across the
  whole run hides exactly this — the reason this lab, like
  clocks-latency-histograms, reports percentiles and never a mean.
- **A collector's name changes the *strategy*, not the physics.**
  `collectorMatrix` runs the IDENTICAL Java code under two different
  collector flags (G1 vs. Parallel) — same allocation pattern, same live
  set, same heap. Any difference observed is attributable to the
  collector's own algorithm (region-based incremental evacuation vs.
  stop-the-world generational copying), never to a difference in what
  the workload does, because there is none.
- **A tiny heap manufactures pauses that a realistic heap would not
  show.** Shrinking the heap forces more frequent collections and can
  push a collector into a degraded mode (G1's full-GC fallback under
  heap pressure) that is not representative of how that collector
  behaves given adequate headroom — this lab's first named trap, and
  exactly why every variant here runs with the identical, deliberately
  non-tiny fixed heap.
- **Rust has none of this to select between.** There is no collector
  flag, no live-set-driven pause, no safepoint-coordinated reclamation —
  `Box`'s memory is freed synchronously, deterministically, the instant
  its owner goes out of scope. The honest Rust-track question is not
  "which Rust collector is fastest" (there isn't one) but "what does
  allocator latency look like instead, and what does reclaiming a large
  retained structure cost as one synchronous event" (rust.md).

## Visualization 1: pause anatomy (deterministic)

What one collection event's duration is made of, and which JFR field
reports it — not a measurement, the textbook decomposition this lab's
harness relies on directly:

| Component | What it covers | JFR event/field |
|---|---|---|
| Collection duration | the VM operation once every thread is stopped | `jdk.GarbageCollection.duration` |
| Sum of sub-phase pauses | individual evacuation/mark/sweep phases within the collection | `jdk.GCPhasePause.duration`, correlated by `gcId` |
| Heap used before/after | how much survived, i.e. the live set | `jdk.GCHeapSummary.heapUsed`, `when="Before GC"`/`"After GC"` |

This lab's harness correlates every event sharing a `gcId` into one
collection episode (java.md), exactly as safepoints-ttsp correlates
events by `safepointId`.

## Visualization 2: three levers, illustrative pattern

A generic sketch of how allocation rate and live-set size can move p99
independently — **illustrative of the general shape, not extracted from
a live run of this lab's code**; the real evidence is this lab's own
harness (benchmark.md):

```text
lowAllocationReuse:     [no GC — nothing to collect]
steadyHighAllocation:   [frequent, cheap collections — small live set]
growingLiveSet:         [collections grow costlier over the run — live set climbing]
burstyAllocation:       [mostly quiet — occasional collection lands mid-burst]
```

## Visualization 3: collector trade-off (conceptual model)

What each collector flag optimizes for — the direct explanation for why
`collectorMatrix` can show a real difference on identical code:

| Collector | Strategy | Expected trade-off |
|---|---|---|
| G1 (`-XX:+UseG1GC`) | region-based, incremental, pause-target-driven | aims for bounded pauses at some throughput cost |
| Parallel (`-XX:+UseParallelGC`) | stop-the-world, generational, throughput-oriented | maximizes throughput, pause length is not a tuning target |

Textual fallback for all three visualizations: a collection's duration is
the VM operation's own cost, driven primarily by live-set size, not
allocation volume; a collector's flag selects a strategy for managing
that cost, not a way to avoid paying it.

## Terminology

- **Live set** — the subset of allocated objects still reachable (and
  therefore not reclaimed) at a given collection.
- **Allocation rate** — bytes allocated per unit time, independent of how
  much of that allocation survives.
- **Young-generation collection** — a collection scoped to
  recently-allocated objects, typically cheap when the live set surviving
  it is small.
- **Evidence maturity** — this lab's numbers are development-machine
  wiring checks until imported from the native-Linux runner
  (`docs/evidence-maturity.md`).

## Assumptions and scope

- Every variant sums the identical deterministic checksum over the
  identical value stream regardless of allocation pattern or collector
  (java.md, rust.md); GC pause counts and durations are measured, never
  correctness-checked, because they are not deterministic quantities.
- Every variant runs with the identical, fixed, non-tiny heap
  (`-Xms512m -Xmx512m`) — deliberately chosen so no result in this lab
  can be attributed to an artificially starved heap.
- The Rust track measures allocator latency and one synchronous
  reclamation event, never a GC pause distribution — that structural
  difference is this lab's central Rust-track finding (rust.md), not a
  gap to close.

## Pre-lab diagnostic — answer

Before accepting "G1 must be misbehaving," ask what service B's
*live-set size and allocation rate* look like relative to service A's —
not just which collector flag each one uses. This lab's hypothesis
predicts exactly this scenario: identical collector, identical code
path, but a larger live set or a burstier allocation pattern on service B
can alone produce a much larger p99, with the collector doing its job
correctly the entire time. Blaming the collector's name without first
comparing live set and allocation rate is precisely the diagnosis this
lab's methodology is built to prevent.
