# Heap vs off-heap with FFM MemorySegment — Java track

Package `pl.kzybala.lab.ffmsegments`: `FfmMemorySegmentsFixtures`
(deterministic source-data generation), the `RecordStorage` interface,
and three per-dataset storage classes
(`FixedRecordsStorage`/`LargeNumericBufferStorage`/`BinaryFramesStorage`),
each providing all five storage variants.

## The pieces

- **`FfmMemorySegmentsFixtures`** — three datasets: `fixedRecords`
  (500,000 records, 24-byte stride), `largeNumericBuffers` (5,000,000
  longs, 40MB — deliberately larger than typical L2/L3 cache),
  `binaryFrames` (100,000 56-byte protocol frames). Also generates a
  fixed, deterministic pseudo-random index stream (`randomIndices`)
  shared by every variant's `randomAccess` benchmark.
- **`RecordStorage`** — the common contract: `sequentialSum()` (one full
  pass) and `randomAccess(int[])` (K reads at fixed indices), both
  operating on an already-built representation — construction happens in
  `@Setup`, never in the timed method.
- **`heapPrimitiveArray`** — plain parallel Java arrays, the GC-managed
  baseline every off-heap variant is measured against.
- **`confinedSegment` / `sharedSegment`** — identical mechanics (offsets
  computed once from a `MemoryLayout`, accessed via plain
  `segment.get/set(ValueLayout, long offset)` — the same
  offset-based technique content/labs/allocation-object-layout's
  `PackedOffHeapStruct` uses, avoiding the layout-path `VarHandle`
  regression documented in content/labs/aos-vs-soa's java.md), differing
  only in `Arena.ofConfined()` vs. `Arena.ofShared()`.
- **`slicedView`** — the payload is written starting at a 4096-byte
  offset into a larger backing segment (simulating a header region
  before the actual data), then accessed through a
  `MemorySegment.asSlice()` view — testing whether slicing itself adds
  access cost beyond a direct segment.
- **`copiedBoundaryCrossing`** — data starts in an off-heap segment (as
  if it arrived from native/FFI code); EVERY call to `sequentialSum()`
  or `randomAccess()` first copies the ENTIRE segment into fresh heap
  arrays, then operates on the copy — the operation is defined as
  "cross the boundary, then process," never a per-index off-heap read
  that would understate the trap.

## A real finding from development wiring (dev-only, never published)

Running the JMH dev benchmark on `fixedRecords` (N = 500,000, K = 10,000
for random access) on this repository's development machine produced
results directly consistent with this lab's hypothesis, with one
genuinely surprising detail: `heapPrimitiveArray`'s `sequentialSum`
(≈0.48 ns/record) was the FASTEST of all five variants — every off-heap
variant (`confinedSegment` ≈1.58, `sharedSegment` ≈1.71, `slicedView`
≈1.24 ns/record) was slower for a straightforward sequential sum, direct
evidence against "off-heap is automatically faster." `slicedView` was
consistently the fastest OFF-HEAP variant on both operations
(sequentialSum ≈1.24 ns/record, randomAccess ≈2.05 ns/access) — slicing
itself added no extra cost, and if anything, correlated with a
marginally simpler access path than a directly-allocated segment on this
run. `copiedBoundaryCrossing` was the clearest, most dramatic result:
its `randomAccess` (≈141.7 ns/access) was roughly **48× slower per
access** than `slicedView`'s (≈2.05 ns/access), because it copies all
500,000 records to service just 10,000 reads — the sharpest
demonstration of the copy-accounting mechanism (theory.md) this lab's
own evidence produced. None of these exact numbers are published
evidence; the directions are what benchmark.md's real evidence is
checked against.

## Build, correctness gate, run

```bash
cd content/labs/ffm-memory-segments/code/java

# correctness gate — every variant sums to the identical total per dataset
mvn test

# build
mvn -q -DskipTests package

# dev/wiring JMH (fixedRecords only, unpinned)
java -jar target/benchmarks.jar FfmMemorySegmentsBenchmark

# THE full-matrix publication benchmark (pinned, via the native-Linux runner)
java -jar target/benchmarks.jar FfmMemorySegmentsLinuxEvidenceBenchmark \
  -p variant=copiedBoundaryCrossing -p dataset=largeNumericBuffers -prof gc
```

## Reading the results

- Always compare `sequentialSum` and `randomAccess` separately —
  `copiedBoundaryCrossing`'s disadvantage is dramatically worse on
  `randomAccess` specifically, because the unconditional whole-dataset
  copy is amortized over far fewer useful reads.
- Compare `confinedSegment` against `sharedSegment` on the SAME dataset
  first — this isolates the arena-kind cost from anything else, since
  both use identical offset-based access code.
- `slicedView` matching (or beating) `confinedSegment`/`sharedSegment`
  is itself a result: it means this lab's slicing technique adds no
  measurable overhead of its own, so a real system can slice freely for
  API convenience without a hidden performance tax.
- Read `-prof gc`'s B/op alongside `copiedBoundaryCrossing`'s ns/op — the
  allocation is the SAME size every call (a fresh N-element array), which
  is precisely why its ns/access degrades so badly as the accessed
  fraction shrinks.
