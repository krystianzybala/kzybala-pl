# Allocation cost and object layout — theory

## Performance question and hypothesis

**Question:** what are the real byte and CPU costs of convenient object
models?

**Hypothesis:** headers, alignment, references and allocator behavior can
make logical data several times larger than packed representations.

**What would disprove it:** if a boxed object graph's measured
bytes-per-record matched a packed struct's byte-for-byte, the premise
that convenience carries a real, measurable footprint cost would be
wrong. Every variant in this lab sums the identical, deterministic
checksum over the identical value stream — representation is the only
thing that changes, and this lab's real evidence (JOL-measured heap
layout, JMH/Criterion-measured construction and read cost, never
estimated) is checked against that. **A record's logical field list and
its physical byte footprint are two different numbers, and the gap
between them is this lab's subject.**

## Learning objective

Calculate a representation's real physical footprint from first
principles (header + fields + alignment + reference indirection), connect
that footprint to cache locality, and choose a representation
deliberately for a given access pattern rather than by habit.

## Prerequisites

- The [Cache locality and working-set size](/lab/cache-locality-working-set/)
  lab (this lab's footprint numbers feed directly into that lab's
  locality question — a smaller footprint means more logical records fit
  in the same cache budget).

## Pre-lab diagnostic

A team stores 10 million small `(x, y)` coordinate pairs as individually
allocated Java objects in an `ArrayList`. Someone proposes switching to
two parallel `int[]` arrays and estimates "we'll save 8 bytes per
record — 80 MB total." Using this lab's mechanism, is that estimate
likely too high, too low, or roughly right, and what is the single
biggest thing it is probably missing?

(Answer at the end of this page.)

## The mechanism: four costs stacked on top of the logical data

- **The object header is fixed overhead, paid once per instance,
  regardless of how small the instance's own fields are.** On a modern
  HotSpot JVM with compressed references, a plain object header is
  typically 12 bytes (an 8-byte mark word plus a 4-byte compressed class
  pointer), rounded up to the JVM's object alignment (commonly 8 bytes).
  A record with two `int` fields (8 logical bytes) can end up needing 16
  or more physical bytes just from header and alignment — a ratio that
  gets *worse*, not better, as the logical record shrinks, which is
  exactly why this lab's `smallTuples` dataset exists.
- **A reference field costs its own slot, separate from what it points
  to.** An object reference is 4 bytes (compressed) or 8 bytes
  (uncompressed) of *pointer*, stored inline in the referencing object,
  on top of the referenced object's own header and fields elsewhere on
  the heap. `treeNodes`' two reference fields (`left`, `right`) each cost
  a slot in every single node, whether or not that node has children —
  this lab's evidence shows this can make a *smaller-payload* record
  (one `long` value) carry a *worse* overhead ratio than a
  larger-payload one, because the reference tax does not scale down with
  the payload.
- **An array of primitives pays the header once, not once per element.**
  A `long[]` or `int[]` array has exactly one header (plus a length
  word) for the whole array, then every element is packed contiguously
  with no per-element tax — the mechanism behind `flatPrimitiveArrays`'
  footprint advantage, and why struct-of-arrays remains a real technique
  even outside of any specific language's object model.
- **Off-heap packing removes the header entirely, but not the access
  cost.** A packed struct in raw memory (this lab's `packedOffHeapStruct`,
  built via Java's `MemorySegment`) pays zero header bytes per record —
  but reading a field back means computing a byte offset and doing an
  explicit typed load, which is not automatically cheaper than a direct
  object field or array-element read. This lab's own measured evidence
  shows the denser representation is not always the faster one to read —
  bytes and CPU cost are genuinely different axes, which is this lab's
  entire performance question, not a corner case.
- **A reused holder amortizes construction, not footprint.** Overwriting
  one persistent object's fields record-by-record avoids allocating N
  separate instances, but it only ever holds one record's worth of data
  at a time — it is not a representation you can index into afterward,
  a real, honest design trade-off (java.md), not a free lunch.
- **Rust's story is structurally different at the header line, identical
  at the alignment line.** A `Box<T>` in Rust carries no mark word and no
  class pointer — its only overhead versus a raw `T` is the pointer
  itself, stored wherever the `Box` lives. Rust's struct layout still
  respects field alignment exactly the way Java's does, which is why the
  "using packed unaligned fields unsafely" trap applies to both
  languages equally (rust.md).

## Visualization 1: byte layout diagram (deterministic)

The physical layout of one `ordersQuotes` record in each representation
— not a measurement, the textbook decomposition this lab's JOL evidence
confirms directly:

| Representation | Layout |
|---|---|
| boxedObjectGraph | `[12-byte header][pad][8B id][8B priceTicks][4B quantity][4B flags]` → JVM-rounded instance size |
| flatPrimitiveArrays | one shared array header per field; each record is 24 logical bytes spread across 4 arrays, no per-record header |
| packedOffHeapStruct | `[8B id][8B priceTicks][4B quantity][4B flags]` = 24 bytes, back-to-back, zero header |
| reusedMutableHolder | one instance's worth of header, amortized across every record in the pass |

## Visualization 2: logical vs physical size (illustrative pattern)

A generic sketch of how the header-to-payload ratio changes with record
size — **illustrative of the general shape, not extracted from a live
run of this lab's code**; the real evidence is this lab's own JOL/JMH/
Criterion output (java.md, rust.md):

```text
ordersQuotes (24 logical bytes): [payload=======][header==]         ratio: smallest
smallTuples  (8 logical bytes):  [payload==][header========]        ratio: worse
treeNodes    (8 logical bytes + 2 refs): [payload==][refs====][header==]  ratio: worst
```

## Visualization 3: allocation flame graph (conceptual model)

Where CPU time goes when constructing N records fresh, boxed vs packed —
the direct explanation for why this lab's `construct` benchmark and its
`sumHot` benchmark can disagree about which representation "wins":

| Phase | boxedObjectGraph | packedOffHeapStruct |
|---|---|---|
| Allocate | N separate heap allocations (or N `malloc` calls in Rust) | one large contiguous allocation |
| Write fields | N small, independent writes | N sequential, offset-computed writes |
| Read back | N pointer dereferences, one per record | N offset computations, decoded per field |

Textual fallback for all three visualizations: header and alignment cost
overhead every representation short of raw packed bytes; that overhead
does not shrink with the payload, so it hurts small records worse than
large ones; and removing it (packing off-heap) trades a byte-footprint
win for an access-pattern cost that must be measured, never assumed.

## Terminology

- **Object header** — the JVM's per-instance bookkeeping (mark word,
  class pointer), paid once per object regardless of field count.
- **Alignment padding** — bytes added so an instance's total size is a
  multiple of the JVM's object alignment (or a field's own natural
  alignment inside a struct).
- **Struct-of-arrays (SoA)** — storing each field as its own dense array
  rather than each record as its own object; this lab's
  `flatPrimitiveArrays` variant.
- **Off-heap packed struct** — a manually laid-out, contiguous byte
  region holding records back-to-back with no per-record header.

## Assumptions and scope

- Every variant sums the identical deterministic checksum over the
  identical value stream regardless of representation (java.md, rust.md);
  footprint and construction/read cost are measured, never
  correctness-checked, because they are not deterministic quantities in
  the same sense.
- JOL's per-instance and per-graph evidence runs on any JVM, including a
  development machine — it introspects the actual running JVM's object
  layout rather than estimating it, so it is real evidence even before
  native-Linux throughput numbers are imported (java.md).
- Rust's `boxedObjectGraph` variant is expected to show zero header
  overhead versus a raw struct, structurally different from Java's — that
  difference is this lab's central Rust-track finding (rust.md), not a
  gap to close.

## Pre-lab diagnostic — answer

The 80 MB estimate is very likely **too low**, and the biggest thing it
is missing is the object header and alignment padding on the *original*
representation, not just the 8 bytes of logical field data. Ten million
individually allocated `(x, y)` objects each carry roughly a 12–16-byte
header (JVM-dependent) on top of their 8 logical bytes — meaning the
*original* representation was likely costing 200–240 MB, not 80 MB, and
the real savings from switching to two `int[]` arrays is closer to
120–160 MB once the header and per-object overhead are counted, not the
8-bytes-per-record delta the estimate started from. This is exactly this
lab's `smallTuples` dataset and its measured header-overhead ratio
(java.md) — small records are where naive "field size" estimates go most
wrong.
