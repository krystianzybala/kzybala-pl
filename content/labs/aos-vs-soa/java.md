# Array of Structures vs Structure of Arrays — Java track

Package `pl.kzybala.lab.aosvssoa`: deterministic record generation shared
with the correctness fixture (`AosVsSoaFixtures`), four layout classes,
real JOL heap-layout evidence, and dev/publication JMH benchmarks.

## The pieces

- **Generation** — `AosVsSoaFixtures.generate(coldWords, seed, n)`
  produces plain `hotA[]`/`hotB[]`/`cold[][]` arrays once; every layout
  class is built *from* those arrays and must reproduce the exact
  `expectedHotSum` — pure integer arithmetic, no floating point, bit-exact
  against the Rust side.
- **`AosHeapLayout`** (variant 1) — one Java object per record
  (`hotA`, `hotB`, plus a reference to its own `long[] cold`): the
  realistic default a Java codebase reaches for.
- **`AosPackedLayout`** (variant 2) — one off-heap `MemorySegment`
  built from a runtime-sized `MemoryLayout.structLayout(...)`
  (`hotA`, `hotB`, a `coldWords`-length sequence), field offsets computed
  once via `MemoryLayout.byteOffset(...)`, then accessed through plain
  `segment.get/set(ValueLayout.JAVA_LONG, offset)` calls in the hot loop.
- **`SoaLayout`** (variant 3) / **`HybridLayout`** (variant 4) — dense
  `long[]` arrays; hybrid interleaves the two hot fields into one array
  instead of two.
- **`JolReport`** — real per-instance (`ClassLayout`) and per-graph
  (`GraphLayout`) heap-layout evidence via `org.openjdk.jol:jol-core`,
  runnable on any JVM (it introspects the live heap, not the target
  publication host specifically).
- **`AosVsSoaBenchmark`** (dev/wiring) — the four layouts over the
  market-quotes dataset, unpinned.
- **`AosVsSoaLinuxEvidenceBenchmark`** (publication) — the full 4×3
  layout×dataset matrix, one pinned worker, the layout-invariance
  correctness oracle re-checked in `@Setup` before any timing.

## A real finding from building this lab: cache VarHandles, or don't use them

The first `AosPackedLayout` implementation used `MemoryLayout`-derived,
multi-level-path `VarHandle`s (`arrayLayout.varHandle(sequenceElement(),
groupElement("hotA"))`) stored as **instance** fields. On this
repository's development machine that version measured **~52.5 ms** per
`sumHot()` call — roughly **60× slower** than every other layout,
including the AoS heap-objects variant it was supposed to beat. Rewriting
`sumHot()`/`coldChecksum()` to use plain `segment.get/set(ValueLayout,
long offset)` with offsets computed once (still derived from the same
`MemoryLayout`, just not accessed through a layout-path `VarHandle` on a
non-constant field) brought it to **~0.88 ms** — in line with the other
layouts. Nothing about the *data* changed; only the access mechanism did.
This is exactly this lab's "measuring construction instead of scan" trap
wearing a different costume: an accidental instrumentation/JIT artifact
that has nothing to do with the layout under test can dominate a naive
benchmark number. The fixed version is what ships in this lab; the
finding itself is why `benchmark.md`'s traps section calls this out by
name rather than only listing it abstractly.

## Build, correctness gate, run

```bash
cd content/labs/aos-vs-soa/code/java

# correctness gate — generation + all four layouts agree, every dataset
mvn test

# build
mvn -q -DskipTests package

# dev smoke — all four layouts, market-quotes dataset (wiring check only)
java -jar target/benchmarks.jar 'AosVsSoaBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true

# one cell of the publication matrix, unpinned dev run
java -jar target/benchmarks.jar 'AosVsSoaLinuxEvidenceBenchmark' \
  -p variant=hybrid -p dataset=positions -f 1 -wi 2 -w 1s -i 3 -r 1s -foe true

# real JOL heap-layout evidence (runs on any JVM, not just the evidence host)
java -cp target/benchmarks.jar pl.kzybala.lab.aosvssoa.JolReport
```

Publication-grade throughput numbers and cache-miss counters come only
from the native-Linux evidence runner (benchmark.md); JOL's layout
evidence is real and portable, but the exact byte counts are specific to
the JDK build and compressed-oops settings of whatever JVM runs it.

## Reading the results

- Compare `soa`/`hybrid` against `aosPacked` on the **same dataset**
  first — same off-heap-vs-not category, isolating pure density.
- Compare `aosPacked` against `aosHeap` second — this isolates the
  managed-heap tax (object header, GC tracing, reference indirection)
  from the layout question, since both store the identical byte content
  per record.
- The gap between `soa` and `hybrid` should be small and dataset-
  independent for this lab's operation (both stream 100% useful bytes);
  a large gap between them on real hardware is worth investigating as an
  artifact before it is trusted (prefetcher behavior with two streams vs
  one, not a layout-density difference).
