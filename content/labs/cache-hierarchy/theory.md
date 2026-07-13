# Cache hierarchy — theory

## Learning objective

Explain why the same amount of work can run orders of magnitude slower
depending only on memory *access pattern*, by relating it to the L1/L2/L3/RAM
hierarchy, cache lines, spatial and temporal locality, and hardware
prefetching — then be able to predict, for a given working-set size and
access pattern, roughly which level of the hierarchy will serve most
accesses.

## Prerequisites

- Comfortable reading Java or Rust code that allocates and iterates arrays.
- No prior knowledge of cache hardware is assumed — this lab builds the
  hierarchy from scratch. (If you've done the [False sharing](/lab/false-sharing/)
  lab, the cache-line concept here is the same one used there.)

## The hierarchy

A modern CPU core does not read main memory (RAM) directly on every access —
RAM latency, on the order of a hundred nanoseconds or more, would leave the
core idle for hundreds of cycles per load. Instead, each core sits behind a
hierarchy of progressively larger, progressively slower caches:

| Level | Typical size (per core, illustrative) | Typical latency (illustrative) |
|---|---|---|
| L1 (split I/D) | 32–192 KB | ~4–5 cycles |
| L2 | 256 KB–2 MB | ~12–20 cycles |
| L3 / LLC (shared) | 4–64 MB | ~40–70 cycles |
| Main memory (RAM) | GBs | ~150–400+ cycles |

<div class="callout"><strong>These numbers are illustrative, not a spec.</strong> Exact sizes and latencies vary by microarchitecture, vendor, and generation — query <code>CPUID</code> (x86) or <code>sysctl hw.*cachesize</code> (Apple silicon/BSD) at runtime if precision matters, the way the False sharing lab treats the 64-byte cache-line size as a common example rather than a guarantee.</div>

When a core requests an address, the hierarchy is checked from L1 outward:
a hit at any level returns the data at that level's latency; a miss falls
through to the next level, all the way to RAM if nothing above holds it.
Because each lower level is also larger, a miss at L1 is often still a hit
one or two levels down — the expensive case is a miss all the way to RAM.

## Cache lines

Every level moves data in fixed-size blocks called **cache lines** —
commonly 64 bytes on current x86-64 and ARM64 parts (see the False sharing
lab for why this is a common example, not a universal constant). Requesting
one byte pulls in the whole 64-byte line containing it. This is the physical
basis for locality: touching one element of an array effectively pre-loads
its neighbours for free, *if* you go on to use them before the line is
evicted.

## Spatial and temporal locality

Two independent properties of an access pattern determine how well it uses
the hierarchy:

- **Spatial locality** — how close together in memory the addresses you
  touch are. Iterating an array index-by-index has excellent spatial
  locality: each 64-byte line serves roughly 8 consecutive `long`s (or 16
  `int`s) before the next line is needed. Chasing pointers scattered across
  a large heap has poor spatial locality: each access may need a fresh line
  that shares nothing with the line before it.
- **Temporal locality** — how soon you return to an address you already
  touched. A loop that repeatedly scans a working set small enough to stay
  resident in a cache level has excellent temporal locality: after the first
  pass warms the cache, every later pass hits it. A single sweep over a
  multi-gigabyte array that's never revisited has poor temporal locality —
  by the time you might return to an early element, it has long since been
  evicted to make room for everything touched since.

Both matter independently. An access pattern can have good spatial locality
but poor temporal locality (one long sequential sweep over data far larger
than any cache level), or the reverse (repeatedly re-reading a single
scattered set of pointers small enough to fit in L1, where each *individual*
pass has poor spatial locality but the working set as a whole stays warm).

## Hardware prefetching

Beyond passively caching what was touched, most cores also *speculatively*
fetch data they predict will be touched soon. The simplest and most common
form is a **stride/stream prefetcher**: when the memory controller observes
a core reading address `A`, then `A + stride`, then `A + 2·stride`, it starts
issuing fetches for `A + 3·stride`, `A + 4·stride`, … *before* the core asks
for them, hiding RAM latency behind the compute already in flight.

This is why sequential access is not simply "as fast as the cache line
happens to be big" — a genuinely large, sequentially-swept array can still
run close to cache-level latency in steady state, because the prefetcher
keeps arriving before the core does. **Random access defeats this
mechanism entirely**: with no detectable stride, the prefetcher has nothing
to predict, and — once the working set exceeds what fits in cache — every
access pays close to full RAM latency, even though the *same number of
bytes* is being read as the sequential case. The interactive model below
demonstrates this directly: sequential access to a working set that
exceeds every cache level still lands mostly in L2 thanks to a simulated
prefetcher; random access over the same size lands in RAM almost every
time.

## Working-set size vs. access pattern: four regimes

Combining "does the working set fit in a given cache level" with "is the
access pattern sequential or random" gives four regimes, which is exactly
what the interactive model's four scenarios let you step through:

1. **Sequential, fits in L1** — cheapest possible case. First pass warms
   the line; every later access, forward or looped, is an L1 hit.
2. **Random, fits in L1** — same eventual steady state as (1) once the
   whole working set has been touched once, because *residency*, not
   order, is what determines an L1 hit for a small enough set — but it
   gets there without the head start hardware prefetching gives sequential
   access.
3. **Sequential, exceeds every cache level** — the working set can never
   fully reside in cache, so pure demand-fetch would miss to RAM on every
   access. Hardware prefetching narrows that gap substantially: most
   accesses land in L2 because the prefetcher stays ahead of the stream.
4. **Random, exceeds every cache level** — the worst case, and the one
   real systems most need to avoid for hot paths: no predictable stride
   means no prefetching benefit, and a working set larger than any cache
   level means almost no reuse either. Close to every access pays RAM
   latency.

## Common mistakes

- **Treating "it's in the cache" as binary.** A cache hierarchy has several
  levels with very different latencies; "cached" without naming a level is
  not precise enough to reason about performance.
- **Assuming sequential access is fast because of cache-line reuse alone.**
  For a working set that exceeds cache capacity, cache-line reuse alone
  (touching 8 elements per fetched line) is not enough to explain observed
  speed — prefetching is usually doing most of the work once the array is
  much larger than any single line.
- **Assuming random access is just "cache-line reuse minus locality."**
  It also loses hardware prefetching entirely, which is often the larger
  effect once the working set exceeds cache.
- **Hardcoding a cache-line size or cache size as an architectural
  constant.** These are hardware facts of the machine you measured on, not
  guarantees — state them as assumptions, or query them at runtime.
- **Benchmarking access patterns without defeating the optimizer.** A
  compiler or JIT can sometimes hoist, vectorize, or otherwise reorder
  simple array loops in ways that no longer reflect the memory-access
  pattern you intended to measure — the benchmark code below uses pointer
  chasing (each access depends on the value of the previous one) specifically
  to prevent that.

## When this matters

- Hot loops over large data structures (arrays, matrices, batch/columnar
  processing) — layout and traversal order can dominate over algorithmic
  micro-optimizations once the data no longer fits in cache.
- Data structure choice for large collections: an array or a struct-of-arrays
  layout gives the prefetcher a stride to find; a linked structure of
  scattered heap objects (linked lists, trees of individually-allocated
  nodes, hash maps with poor locality) does not.
- Anywhere "why is this small, algorithmically-correct change 5× slower"
  shows up — reordering loop nests, switching array-of-structs to
  struct-of-arrays, or changing a traversal order can move a workload
  between these four regimes without changing what it computes.

## When this matters less

- Data that already comfortably fits in L1/L2 for the whole hot path — the
  difference between access patterns shrinks to nothing once nothing ever
  has to leave the fastest level (see scenario 1 vs. 2 above).
  Micro-optimizing traversal order here has a low ceiling.
- I/O-bound or network-bound workloads where memory latency is not the
  bottleneck to begin with — profile before assuming cache behaviour is
  the dominant cost.

## Investigation task

Using the Java or Rust project in `code/`, and a profiler or hardware
counter tool available to you:

1. Run all four benchmarks (`sequentialSmall`, `randomSmall`,
   `sequentialLarge`, `randomLarge` in Java; the equivalent Criterion
   functions in Rust) and record the reported time per batch for each.
2. Compute an approximate nanoseconds-per-access figure for each by
   dividing the reported batch time by the number of chases per batch
   (see `benchmark.md` for the exact count used in the disclosed run).
3. If you have `perf` (Linux) or Instruments (macOS) available, look for a
   last-level-cache-miss or memory-stall counter and compare it between the
   `*Small` and `*Large` variants — confirm the miss rate rises sharply
   only for the `*Large` benchmarks, not the `*Small` ones.
4. Change `LARGE_SIZE` (Java) / `LARGE_SIZE` (Rust) to a value that fits
   inside your machine's actual LLC size (check it first) and re-run
   `sequentialLarge`/`randomLarge` — confirm the sequential/random gap
   narrows once the working set fits in cache, matching the "fits" vs.
   "exceeds" scenarios in the interactive model above.
5. Run each benchmark twice — once idle, once with a CPU-heavy background
   task running — and compare the reported error bars. Expect the
   `random*` benchmarks to show much more run-to-run variance under load
   than the `sequential*` ones; `benchmark.md`'s "Known limitations"
   section has a concrete example of this from this lab's own reruns.
6. Write down your CPU model, cache sizes (per level, if you can find
   them), and your measured numbers, and explain any difference from the
   disclosed numbers in `benchmark.md`.

## Limitations of this model

- The interactive visualisation uses illustrative cache capacities (4/8/16
  lines for L1/L2/L3) and a simplified single-stride prefetcher — real
  hardware prefetchers detect multiple concurrent streams, variable
  strides, and use far larger structures than this model's three-level LRU
  cascade.
- Latency numbers in the table above are order-of-magnitude illustrations
  from public microarchitecture documentation and common knowledge, not a
  cycle-accurate simulation and not identical to the machine the measured
  benchmark below ran on.
- The benchmark's pointer-chase technique isolates *latency* (one
  dependent access at a time) — it deliberately does not measure
  *bandwidth* (how fast independent, parallel streams can move bytes),
  which is a related but different property of the memory subsystem.
