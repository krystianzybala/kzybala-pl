# Allocation cost and object layout — Java track

Package `pl.kzybala.lab.objectlayout`: `AllocationObjectLayoutFixtures`
(deterministic source-data generation), `OrderRecord`/`TreeNode`/`Tuple`
(the three logical record shapes), the four representation variants
(`BoxedObjectGraph`, `FlatPrimitiveArrays`, `PackedOffHeapStruct`,
`ReusedMutableHolder`), `JolReport` (real heap-layout evidence) and two
JMH benchmark classes.

## The pieces

- **`AllocationObjectLayoutFixtures`** — three datasets: `ordersQuotes`
  (200,000 records, 4 fields), `treeNodes` (200,000 records, 1 field plus
  index-implicit structure), `smallTuples` (1,000,000 records, 2 fields —
  the smallest record, deliberately, to make header overhead starkest).
  Every representation reads the identical source arrays for a given
  dataset.
- **`BoxedObjectGraph`** — one heap object per record. For
  `treeNodes` this is a genuine linked graph: real `left`/`right` object
  references connecting `TreeNode` instances, summed by iterative
  pointer-chasing traversal — not index arithmetic standing in for a
  tree, an actual one.
- **`FlatPrimitiveArrays`** — struct-of-arrays: every field its own
  dense primitive array, no per-record object at all.
  `treeNodes`' structure is index-implicit (`child(i) = 2i+1, 2i+2`, the
  classic complete-binary-tree-as-array technique), traversed via
  explicit index arithmetic — a real tree traversal, not a linear scan
  standing in for one.
- **`PackedOffHeapStruct`** — one contiguous `MemorySegment` per dataset,
  every field at a natural, alignment-respecting offset (id/priceTicks
  8-byte-aligned, quantity/flags 4-byte-aligned — this lab's "using
  packed unaligned fields unsafely" trap named exactly the mistake this
  layout avoids). Offsets are computed once from a `MemoryLayout` and
  accessed via plain `segment.get/set(ValueLayout, long offset)` — see
  content/labs/aos-vs-soa's java.md for the real ~100x C2 regression a
  layout-path `VarHandle` caused when this repository first built a
  packed layout; this lab reuses the offset-based technique specifically
  to avoid repeating it.
- **`ReusedMutableHolder`** — one instance per pass, its fields
  overwritten from the source arrays record by record. No separate
  "build once, read many" phase exists for this variant — streaming
  through the source IS the operation (see the JMH class doc below).
- **`JolReport`** — real per-instance (`ClassLayout`) and per-graph
  (`GraphLayout`) heap-layout evidence via JOL. This introspects the
  actual running JVM's object layout — captured evidence, not an
  estimate — and runs on any JVM including a development machine.

## A real finding from development wiring (dev-only, never published)

Running `JolReport` on this repository's development machine produced
the header-overhead ratio (boxed bytes/record ÷ packed bytes/record)
this lab's hypothesis predicts, but with a size-dependent twist: `ordersQuotes`
(24 packed bytes/record) showed **1.83×** overhead (44.0 boxed
bytes/record); `smallTuples` (8 packed bytes/record) showed **3.50×**
(28.0 boxed bytes/record); and `treeNodes` (8 packed bytes/record, but
carrying two reference fields) showed the *worst* ratio of the three at
**4.00×** (32.0 boxed bytes/record) — direct confirmation that reference
fields add their own tax independent of payload size, exactly this lab's
mechanism section. Running the JMH dev benchmark (`ordersQuotes` only, N
= 200,000) produced a genuinely surprising result worth stating plainly:
`packedOffHeapStruct`'s `sumHot` (403,119 ns) was **slower** than
`boxedObjectGraph`'s (172,375 ns) despite being 45% smaller in bytes —
`MemorySegment` access carries its own per-call overhead that plain
object field reads do not, so the denser representation was not the
faster one to read back on this run. `flatPrimitiveArrays` was fastest
at both `construct` (254,752 ns) and `sumHot` (72,150 ns);
`reusedMutableHolder`'s `construct` and `sumHot` converged to nearly the
identical number (120,918 ns / 118,678 ns) as expected, since both time
the same underlying stream. None of these exact numbers are published
evidence; the directions are what benchmark.md's real evidence is
checked against.

## Build, correctness gate, run

```bash
cd content/labs/allocation-object-layout/code/java

# correctness gate — every variant sums to the identical total per dataset
mvn test

# build
mvn -q -DskipTests package

# real heap-layout evidence (any JVM, including this dev machine)
java -cp target/benchmarks.jar pl.kzybala.lab.objectlayout.JolReport

# dev/wiring JMH (ordersQuotes only, unpinned)
java -jar target/benchmarks.jar AllocationObjectLayoutBenchmark

# THE full-matrix publication benchmark (pinned, via the native-Linux runner)
java -jar target/benchmarks.jar AllocationObjectLayoutLinuxEvidenceBenchmark \
  -p variant=packedOffHeapStruct -p dataset=treeNodes -prof gc
```

## Reading the results

- Always read `construct` and `sumHot` as two separate metrics, never
  averaged together — a variant can win one and lose the other, exactly
  as `packedOffHeapStruct` does on this dev machine (dense but slower to
  read).
- Compare JOL's bytes/record evidence against `-prof gc`'s B/op evidence
  from the SAME dataset — JOL reports the persisted footprint, `-prof gc`
  reports allocation churn during `construct`; a variant can be small at
  rest and still allocate heavily while building (`boxedObjectGraph`),
  or the reverse.
- `treeNodes`' header-overhead ratio being the worst of the three
  datasets, despite having the smallest per-node payload, is the direct
  evidence for this lab's "reference fields add their own tax"
  mechanism point — read it alongside `smallTuples`' ratio, not instead
  of it, to separate the two effects (payload size vs. reference count).
- `reusedMutableHolder`'s `construct` and `sumHot` numbers converging is
  itself a result, confirming there is no separate materialized
  structure for this variant to read back — not a benchmarking artifact.
