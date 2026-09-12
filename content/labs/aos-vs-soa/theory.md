# Array of Structures vs Structure of Arrays — theory

## Performance question and hypothesis

**Question:** how does record layout affect cache density, SIMD potential
and update cost?

**Hypothesis:** SoA improves read-mostly scans over selected fields,
while AoS can remain superior for whole-record operations and simpler
ownership.

**What would disprove it:** if a hot-field-only scan cost the same
regardless of layout, if AoS's extra bytes per cache line never showed up
as more cache misses per record, or if a fully split SoA layout were
never beaten by any alternative on any access pattern, the premise would
be wrong. Every layout in this lab stores the *exact same generated
values* — only their arrangement in memory differs — and a shared
correctness oracle (java.md, rust.md) asserts that the hot-field sum is
bit-identical across all four layouts before any timing is trusted:
**layout changes storage, never the data.**

## Learning objective

Choose a record layout from its access pattern, quantify padding and
density instead of guessing at them, and explain when a hybrid hot/cold
split beats both pure AoS and pure SoA.

## Prerequisites

- The [Cache locality and working set](/lab/cache-locality-working-set/)
  lab (cache lines, working-set sizing and the sequential-vs-random access
  distinction are assumed here).
- Basic familiarity with heap allocation cost and reference indirection in
  a managed runtime.

## Pre-lab diagnostic

A team stores order-book positions as one class per position — symbol,
quantity, average price, account id, risk limits, timestamps, audit
flags: fifteen fields in one object. Their risk engine scans a million
positions every second, reading only `quantity` and `averagePrice` to
compute exposure. Someone proposes "just switch everything to SoA." Name
one reason that might be the wrong fix, and one reason it might be
exactly right.

(Answer at the end of this page.)

## The mechanism: what the CPU actually loads

- **A cache line is the real unit of transfer, not a field.** When the
  CPU reads one `long` field, the memory system does not fetch 8 bytes —
  it fetches the entire 64-byte cache line containing those bytes. If a
  record interleaves two hot fields with many cold ones, every hot-field
  access drags the whole record's cold bytes along for free — bandwidth
  spent on data the operation never uses.
- **Array of Structures (AoS)** stores each record together: `[hotA₀,
  hotB₀, cold₀…, hotA₁, hotB₁, cold₁…, …]`. Any operation touching one
  full record is one cache-line-friendly access; an operation touching
  only two of fifteen fields still pays for the whole record's footprint
  per cache line, proportionally more so the larger the cold payload.
- **Structure of Arrays (SoA)** stores each field in its own dense array:
  `[hotA₀, hotA₁, hotA₂, …]`, `[hotB₀, hotB₁, hotB₂, …]`, and so on. A
  scan touching only `hotA` and `hotB` streams two fully-dense arrays —
  every byte fetched is a byte used — and the CPU's hardware prefetcher
  has the easiest possible job: constant-stride, single-direction access.
- **Density is a real, computable number, not a feeling.** *Bytes touched
  per useful byte* for an AoS record with `H` hot bytes and `C` cold bytes
  is `(H+C)/H`; the identical scan over SoA is `1.0` by construction. For
  this lab's "positions" dataset (16 hot bytes, 64 cold bytes), AoS moves
  `80/16 = 5×` the bytes SoA needs for the same useful data — a number
  this lab's evidence panel checks against real cache-miss counters,
  not just arithmetic.
- **The hybrid hot/cold split is not "SoA but worse."** It packs the hot
  fields *together* (interleaved, like a mini-AoS of only the hot fields)
  while moving cold fields to a separate region entirely. When an
  operation always needs several hot fields *together* — as this lab's
  `hotA + hotB` scan does — one interleaved stream can match or beat two
  fully separate streams from full SoA, because the CPU issues one stream
  of loads instead of two independently-strided ones. This is the
  mechanistic reason "assuming SoA always wins" is a named trap, not a
  strawman.
- **Java's AoS has a cost SoA's arrays never pay: the object header.**
  A Java object carries a mark word and a class pointer (commonly 12–16
  bytes with compressed references) before a single field of yours is
  stored, and a field that is itself an array (this lab's cold payload)
  is a *separate* heap object with its own header — an extra allocation,
  an extra pointer, and one more thing the garbage collector has to trace.
  Primitive arrays (SoA's `long[]`, `int[]`) carry only one header for the
  *entire* array, amortized over every element. JOL (java.md) measures
  this exactly rather than estimating it.
- **Vector API / SIMD eligibility follows density, not intent.** A JIT or
  explicit SIMD API can only vectorize a loop over data that is
  contiguous and stride-1 for the accessed fields — which SoA's
  `hotA[]`/`hotB[]` are, and AoS's interleaved records are not (a
  vectorized load would need a stride equal to the *whole record size*,
  which defeats most SIMD gather/pack strategies on common hardware).
  This lab discusses vectorization *eligibility* as a structural property
  of the layout — whether the loop shape permits it — rather than
  requiring a specific SIMD implementation to prove the point.
- **Off-heap layouts (Java `MemorySegment`, Rust `#[repr(C)]`) remove the
  managed-runtime tax entirely** — no per-object header, no GC tracing,
  an explicit, inspectable byte layout — at the cost of manual lifetime
  management and losing the object model's safety net. This lab's
  "AoS packed/off-heap" variant is exactly this trade, measured against
  heap-object AoS on the *same* data.

## Visualization 1: record layout diagram (deterministic, byte-accurate)

This lab's own three datasets, computed directly from each layout's
declared stride — not measured, just arithmetic:

| Dataset | Hot bytes | Cold bytes | AoS packed stride | SoA useful-byte ratio | AoS packed useful-byte ratio |
|---|---|---|---|---|---|
| spatialPoints | 16 | 16 | 32 bytes/record | 1.0 | 0.50 |
| marketQuotes | 16 | 32 | 48 bytes/record | 1.0 | 0.33 |
| positions | 16 | 64 | 80 bytes/record | 1.0 | 0.20 |

"Useful-byte ratio" = hot bytes ÷ total bytes touched per record by a
hot-field-only scan. AoS heap objects (Java) add a further, JVM-specific
tax on top of the packed stride — see java.md for the real measured
number.

## Visualization 2: cache-line occupancy (deterministic model)

How many *useful* (hot) bytes land in each 64-byte cache line fetched by
a hot-field-only scan, per layout, for the "positions" dataset (16 hot +
64 cold bytes/record):

| Layout | Bytes/cache-line fetch that are hot | Cache lines touched per 1,000 records (approx.) |
|---|---|---|
| AoS packed (80 B/record) | 16/80 ≈ 20% | ⌈1000×80/64⌉ ≈ 1,250 |
| AoS heap (Java, header + indirection) | lower still (header/ref overhead is pure waste) | more than packed — see java.md's JOL numbers |
| SoA | 100% (every fetched byte is hotA or hotB) | ⌈1000×16/64⌉ ≈ 250 |
| Hybrid | 100% for the hot stream (cold is a separate, unfetched region) | ⌈1000×16/64⌉ ≈ 250, in ONE interleaved stream instead of two |

SoA and hybrid fetch the same number of cache lines for this scan; the
difference between them is stream *count*, not byte waste — see the
theory mechanism above for why that still matters.

## Visualization 3: throughput by accessed-field ratio (conceptual model)

A conceptual sketch of how each layout should respond as the *fraction of
fields actually read* changes, holding the operation's total useful work
fixed — the direction the real perf-counter evidence (benchmark.md)
should confirm, not a measurement itself:

| Fraction of record's fields read | AoS (packed or heap) | SoA | Hybrid |
|---|---|---|---|
| all fields (whole-record op) | best — one contiguous fetch per record | worst — one fetch per field array, per record | worst for cold fields, best for the hot subset |
| few fields, all "hot" together | poor — cold bytes ride along for free | good | best — one stream, same fields |
| few fields, scattered/independent | poor | best | good for the grouped ones, poor for the rest |

The lesson this table is built to prevent skipping: SoA's advantage is
conditional on *which* fields a given operation reads, not a property of
SoA in the abstract.

## Terminology

- **Cache line** — the fixed-size (commonly 64 bytes) unit a CPU actually
  transfers between memory and cache; the true unit of "how much data did
  this load cost."
- **AoS / SoA** — array-of-structures (one array, whole records) vs
  structure-of-arrays (one array per field).
- **Hot / cold fields** — fields an operation reads frequently vs rarely;
  a property of the *access pattern*, not the data itself.
- **Density / useful-byte ratio** — the fraction of bytes fetched by an
  operation that the operation actually uses.
- **SIMD / Vector API eligibility** — whether a loop's data layout and
  stride permit vectorized (wide, contiguous) loads; a structural
  property checked before any vector code is written.
- **Off-heap / `MemorySegment` / `#[repr(C)]`** — memory outside the
  managed heap (Java) or a struct whose field layout is guaranteed
  stable and C-compatible (Rust), both used here for the AoS packed
  variant.

## Assumptions and scope

- All four layouts store the identical generated values for a given
  dataset; the shared fixture and the layout-invariance oracle (java.md,
  rust.md, benchmark.md) prove this before any timing is trusted.
- The operation is deliberately narrow — sum the two hot fields over
  every record — so that density, not algorithmic complexity, is the
  variable under test.
- "Vectorization eligibility" is discussed as a structural property of
  each layout's loop shape; this lab does not require a hand-written
  SIMD implementation to make that point (java.md, rust.md).
- Java object header size, compressed-oops behavior and exact byte counts
  are JVM-build- and architecture-specific; this page's byte-accurate
  tables use declared strides (portable arithmetic), while java.md's real
  JOL numbers are captured evidence from a specific JVM and are presented
  as such.

## Pre-lab diagnostic — answer

Both reasons are real, and they are why this lab's hypothesis is
conditional rather than absolute. **Full SoA might be wrong**: if the
risk engine (or an audit process, or a snapshot job) ever needs *whole*
positions — not just quantity and price — splitting fifteen fields into
fifteen separate arrays turns every whole-record read into fifteen
independent, non-contiguous fetches, which is worse than the original AoS
for that access pattern. **A hot/cold split is exactly right for the
stated workload**: pulling just `quantity` and `averagePrice` into a
dense, interleaved pair (this lab's "hybrid" layout) gives the risk scan
the same density SoA would, without fragmenting the other thirteen fields
that whole-record consumers still need contiguous. The fix is "identify
the actual hot fields and group them," not "split everything" — which is
this lab's hybrid variant, and its main point.
