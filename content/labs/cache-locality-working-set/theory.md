# Cache locality and working-set size — theory

## Performance question and hypothesis

**Question:** why does the same loop collapse when the working set moves
from L1 to L2, L3 and DRAM?

**Hypothesis:** access order and footprint determine cache-miss rates and
memory-level parallelism more strongly than source-language syntax.

**What would disprove it:** if ns/element stayed flat as the working set
crossed every cache-capacity boundary, if a dependent pointer chase and
an independent random-order scan over the identical data cost the same,
or if blocking a column-major traversal into cache-sized tiles made no
measurable difference, the premise would be wrong. Every variant in this
lab visits the identical values `0..n` — only the order changes — and a
shared correctness oracle proves every variant sums to the same
`n·(n−1)/2` before any timing is trusted: **traversal order changes cost,
never the result.**

## Learning objective

Reason about a working set in bytes, not element counts; recognize a
cache-capacity transition in a latency curve; and design a
locality-friendly traversal instead of guessing at one.

## Prerequisites

- The [Benchmark harness traps](/lab/benchmark-harness-traps/) and
  [Clocks, latency histograms and percentiles](/lab/clocks-latency-histograms/)
  labs (harness discipline and reading a latency distribution, not just
  its mean, are assumed here).
- Basic familiarity with what a CPU cache is (this lab teaches the
  capacity and access-order mechanics specifically).

## Pre-lab diagnostic

A benchmark reports: scanning a 16 KB array takes 4 μs; scanning a
128 MB array of the *same shape* takes 40 ms — not 8,000× more elements'
worth of proportional time, but a full order of magnitude *worse per
element* than the small case. The team concludes "big arrays are slow."
What single word, absent from that sentence, is actually doing the work?

(Answer at the end of this page.)

## The mechanism: capacity, order and independence

- **A working set either fits in a cache level or it doesn't — there is
  no partial credit.** As a repeatedly-accessed dataset's byte footprint
  grows past L1's capacity, then L2's, then the LLC's, each crossing is a
  step change: the *portion* of accesses serviced from that level drops
  toward zero and the *portion* serviced from the next, slower level
  rises to compensate. The result is a staircase in ns/element, not a
  smooth curve — this lab's datasets (L1-sized, L2-sized, LLC-sized,
  2×LLC, large/DRAM-resident) are chosen specifically to straddle each
  step.
- **Sequential access is the hardware's best case.** A stride-1 scan lets
  the prefetcher predict every future access correctly, and consecutive
  elements share cache lines — one miss services many subsequent hits.
- **A random but *independent* access pattern is not as bad as it looks.**
  Visiting elements in a shuffled order defeats the prefetcher's stride
  prediction, but each load's address does not depend on a previous
  load's *value* — the CPU can have several cache misses in flight at
  once (memory-level parallelism), overlapping their latencies instead of
  paying each one serially.
- **A dependent pointer chase removes that overlap entirely.** When each
  load's address is computed *from the value just loaded*
  (`idx = next[idx]`), the CPU cannot start the next load until the
  current one completes — every miss is paid in full, one at a time. This
  is why this lab (like the reference cache-hierarchy lab) uses pointer
  chasing specifically to isolate raw memory *latency* from bandwidth or
  parallelism effects: it is the pattern with the least possible hardware
  help.
- **Blocking/tiling recovers locality for an access order that is
  otherwise unfriendly.** A column-major pass over a row-major matrix is
  close to worst-case (each step strides a full row, touching a new cache
  line almost every time). Decomposing that same traversal into small
  tiles — finish one small block of rows×columns before moving to the
  next — keeps each tile's working set inside a fast cache level for the
  duration of its own processing, even though the *overall* traversal is
  still nominally column-oriented. Blocking does not change *what* is
  computed, only the *order*, which is exactly this lab's controlled
  variable.
- **Reasoning in elements instead of bytes is the most common mistake
  this lab exists to correct.** "One million elements" means nothing
  about cache behavior on its own — one million bytes and one million
  16-byte records cross completely different cache boundaries. Every
  dataset here is named and sized by its *byte* footprint relative to the
  detected topology, never by a fixed element count.

## Visualization 1: memory hierarchy diagram (deterministic)

Typical latency and capacity relationships between levels (order-of-
magnitude, host-independent illustration — this lab's own measured
latencies, per level, are the real evidence in benchmark.md):

| Level | Typical capacity | Typical latency (cycles, illustrative) |
|---|---|---|
| L1d | tens of KB | ~4–5 |
| L2 | hundreds of KB to low MB | ~12–20 |
| LLC (L3) | several MB to tens of MB | ~30–70 |
| DRAM | GBs | ~150–300+ |

Each row is roughly an order of magnitude slower than the one above it —
which is exactly why a working set crossing a capacity boundary produces
a *step*, not a gradual slope.

## Visualization 2: latency staircase (conceptual model)

The expected shape of ns/element as working-set size grows past each
boundary, for the sequential and pointer-chase variants specifically —
the direction benchmark.md's real counters must confirm:

| Working set | vs. L1 | vs. L2 | vs. LLC | Expected sequential ns/element | Expected pointer-chase ns/element |
|---|---|---|---|---|---|
| L1-sized | fits | fits | fits | lowest | low (still one full miss-class better than the rest) |
| L2-sized | exceeds | fits | fits | small step up | noticeably higher (L2 latency, fully serialized) |
| LLC-sized | exceeds | exceeds | fits | another step up | higher again (LLC latency, fully serialized) |
| 2×LLC | exceeds | exceeds | exceeds | large step up | large step up (DRAM latency begins to dominate) |
| large (DRAM-resident) | exceeds | exceeds | exceeds | plateaus near DRAM bandwidth limits | plateaus near full DRAM latency — the worst case this lab produces |

The gap between the sequential row and the pointer-chase row *widens* as
the working set grows past the LLC — sequential access keeps getting
prefetcher and memory-level-parallelism help that a dependent chain
never can.

## Visualization 3: working-set crossover chart (conceptual model)

How the four variants should rank, qualitatively, for a working set that
clearly exceeds the LLC:

| Variant | Relative cost (largest working set) | Why |
|---|---|---|
| sequential | lowest | maximal spatial locality, full prefetcher benefit |
| blocked/tiled | low, close to sequential | each tile is locality-friendly even though the overall pass is column-major |
| random (independent) | higher | prefetcher defeated, but memory-level parallelism still overlaps misses |
| pointer chasing | highest | prefetcher defeated AND no overlap possible — every miss paid serially |

Textual fallback for all three visualizations above: as a working set
crosses each cache-capacity boundary, ns/element jumps in a step, not a
slope; a dependent pointer chase pays each miss's full latency serially
and is therefore the slowest pattern at any given working-set size;
blocking a naturally unfriendly traversal into small tiles recovers most
of sequential access's benefit without changing what is computed.

## Terminology

- **Working set** — the set of bytes an operation repeatedly touches;
  what matters for cache behavior is its byte footprint, not its element
  count.
- **Cache-capacity transition / crossover** — the working-set size at
  which a cache level stops being able to hold the data, producing a
  step change in measured latency.
- **Memory-level parallelism (MLP)** — the CPU's ability to have multiple
  outstanding cache misses in flight simultaneously, when their
  addresses do not depend on each other.
- **Dependent access chain** — a sequence of loads where each address is
  computed from the previous load's *value*, which forecloses MLP.
- **Blocking / tiling** — decomposing a traversal into small, cache-sized
  chunks processed one at a time, to recover locality for an access
  pattern that would otherwise be unfriendly.

## Assumptions and scope

- Every variant's payload is the trivial sequence `0..n` (`values[i]=i`);
  only the traversal *order* differs, which sidesteps any cross-language
  data-generation mismatch for the payload itself and isolates the
  variable this lab is actually about.
- Working-set sizes are derived from the DETECTED cache topology of the
  measuring host, never a hardcoded byte count (java.md, rust.md); a
  development host without `/sys/devices/system/cpu/*/cache` uses a
  documented, explicitly flagged fallback that is never publication data.
- Page faults during measurement and NUMA migration are named traps
  (benchmark.md) specifically because both introduce latency unrelated to
  the cache mechanism under test; datasets are fully pre-touched in setup
  and workers are pinned before timing begins.

## Pre-lab diagnostic — answer

The missing word is **footprint** (equivalently: *which cache level the
data fits in*). The two arrays are not "small" and "big" in some vague
sense — one fits comfortably inside L1 and the other cannot fit in any
cache level on the host, so it is served almost entirely from DRAM. The
~10,000× time ratio is not "bigger is proportionally slower"; it is the
sum of thousands of individually-cheap L1 hits in one case against
mostly full DRAM-latency misses in the other — the same mechanism this
lab's staircase visualization models, just at the widest possible gap.
