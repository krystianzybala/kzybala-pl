# Heap vs off-heap with FFM MemorySegment — theory

## Performance question and hypothesis

**Question:** when does moving data off heap help, and what costs
replace GC-managed objects?

**Hypothesis:** FFM can provide explicit layout and lifetime control,
but access checks, arenas, copying and ownership errors can erase gains.

**What would disprove it:** if every off-heap variant in this lab were
uniformly faster than the heap-array baseline regardless of access
pattern, the premise that off-heap carries its own real costs — not just
its own benefits — would be wrong. Every variant in this lab reads the
identical value stream and sums to the identical checksum; only where
the data lives and how it is reached changes, and this lab's real
evidence (JMH/Criterion-measured, never estimated) is checked against
that. **Off-heap is a lifecycle and layout tool, not a performance
button.**

## Learning objective

Choose heap vs. off-heap storage from a data structure's actual
lifecycle and interop needs, use `MemorySegment`s safely (confinement,
arena scope, slicing), and account honestly for the cost of copying data
across the heap/off-heap boundary rather than assuming it away.

## Prerequisites

- The [Allocation cost and object layout](/lab/allocation-object-layout/)
  lab (this lab's `confinedSegment`/`sharedSegment` variants reuse that
  lab's offset-based packed-struct technique directly).
- The [Bounds checks and loop shape](/lab/bounds-checks-loop-shape/) lab
  (`MemorySegment` access carries its own bounds/access-mode checks,
  conceptually related to the array bounds-check-elimination mechanism
  that lab covers, though the JIT's ability to eliminate them differs).

## Pre-lab diagnostic

A team receives binary sensor data over a socket and stores it in a
`MemorySegment` for zero-copy access. Later, a reporting job needs to
call a third-party library that only accepts `long[]`. A developer adds
a one-line conversion (`segment.toArray(ValueLayout.JAVA_LONG)`) inside
the reporting job's hot loop, runs it once per report, and moves on.
Six months later, the reporting job is identified as the service's
single biggest CPU consumer. Using this lab's mechanism, what is the
most likely cause, and which of this lab's named traps does it match?

(Answer at the end of this page.)

## The mechanism: off-heap trades one set of costs for another

- **A `MemorySegment` has no JVM object header, but every access has its
  own cost.** Off-heap memory is not tracked by the garbage collector and
  carries no per-object header — but every `segment.get`/`set` call goes
  through the FFM API's own access-mode and bounds checks, which are not
  automatically cheaper than a plain array or field read. This lab's own
  measured evidence shows off-heap segment access frequently *slower*
  than a heap array for the identical logical operation — not because
  FFM is poorly implemented, but because "no header" and "fast access"
  are different claims.
- **Confined vs. shared arenas encode WHO may touch the memory, not how
  fast the memory is.** `Arena.ofConfined()` restricts access to the
  thread that created it (checked at access time); `Arena.ofShared()`
  allows access from any thread, with its own bookkeeping cost. Neither
  is "the fast one" by default — the choice is about the actual
  lifecycle and threading model of the data, exactly this lab's learning
  outcome.
- **A slice is a view, not a copy — but it is still a view someone must
  keep alive.** `MemorySegment.asSlice()` shares the backing memory and
  its lifetime with the segment it was sliced from; using a slice after
  its backing arena closes is undefined the same way using ANY memory
  from a closed arena is. This lab's `slicedView` variant demonstrates
  that slicing itself adds no fundamental new access cost — the offset
  math is the same shape as a direct segment.
- **Closing an arena while another thread or a live slice still holds a
  reference to it is a real, catchable class of bug, not a theoretical
  one.** This is this lab's "closing arena during use" trap: a confined
  arena will throw on cross-thread access; a shared arena will not
  necessarily fail immediately, which can make the bug look like it
  "worked" until a specific timing window exposes it.
- **Crossing the heap/off-heap boundary means an actual copy, and that
  copy is not free.** When off-heap data must reach a heap-only API — or
  heap data must reach an off-heap/native one — someone pays to copy
  every byte. This lab's `copiedBoundaryCrossing` variant makes that copy
  an explicit, measured part of the operation, and this lab's own
  evidence shows it can dominate the entire operation's cost, especially
  when only a small fraction of the copied data is actually used
  (`randomAccess`'s K=10,000 reads against a 5,000,000-record buffer is
  the sharpest version of this).
- **"Off-heap is automatically faster" is the trap this lab's whole
  hypothesis exists to correct.** The mechanism gives you explicit
  layout and lifetime control — genuinely useful for interop, huge
  datasets that would pressure the GC, or predictable memory footprint —
  but none of that is the same claim as "faster to read," and this lab's
  own measured evidence shows the heap array baseline winning the
  sequential-read comparison on this dev machine, not losing it.
- **Rust has no separate off-heap concept to reach for.** A `Vec<T>`
  already IS native, GC-free, explicitly-owned memory — what Java's FFM
  API exists to approximate. Rust's own "confined vs. shared" analog is
  single ownership vs. `Arc`'s atomic reference counting, and its
  "closing arena during use" trap is structurally prevented at compile
  time by the borrow checker, not caught at runtime the way Java's is
  (rust.md).

## Visualization 1: heap/off-heap ownership diagram (deterministic)

Who owns the memory and what enforces its lifetime, per variant — not a
measurement, the textbook decomposition this lab's implementation
relies on directly:

| Variant | Memory owner | Lifetime enforced by |
|---|---|---|
| heapPrimitiveArray | the JVM heap, GC-tracked | the garbage collector |
| confinedSegment | one `Arena.ofConfined()`, one thread | the arena, checked per access |
| sharedSegment | one `Arena.ofShared()`, any thread | the arena, checked per access |
| slicedView | a slice of a larger arena-owned segment | the BACKING arena — the slice has no independent lifetime |
| copiedBoundaryCrossing | starts off-heap, ends as a fresh heap copy every operation | both the arena AND the GC, for different halves of the data's life |

## Visualization 2: access-path assembly (illustrative pattern)

A generic sketch of what each access path does before returning a value
— **illustrative of the general shape, not extracted from a live run of
this lab's code**; the real evidence is this lab's own JMH/Criterion
output (java.md, rust.md):

```text
heapPrimitiveArray:  [array bounds check][field read]
confinedSegment:     [confinement check][bounds check][typed read]
slicedView:          [confinement check][bounds check against slice length][typed read]
copiedBoundaryCrossing: [confinement check][bounds check][typed read] × N  →  THEN the requested access
```

## Visualization 3: copy accounting view (conceptual model)

Where `copiedBoundaryCrossing`'s cost actually goes, and why it scales
with total dataset size regardless of how much is used:

| Operation | Bytes touched | Bytes copied |
|---|---|---|
| `sequentialSum` on a 500,000-record dataset | all 500,000 records | all 500,000 records |
| `randomAccess` (K=10,000) on the SAME dataset | 10,000 records | still all 500,000 records — the copy is unconditional |

Textual fallback for all three visualizations: off-heap memory removes
the GC and the object header, but adds an access-mode check and a
lifetime the arena (not the collector) is responsible for; a slice
shares its backing's lifetime rather than owning one; and crossing the
boundary means copying every byte the segment holds, whether or not the
operation actually needed all of it.

## Terminology

- **Arena** — the FFM API's lifetime/ownership boundary for one or more
  `MemorySegment`s; closing it invalidates every segment it allocated.
- **Confined arena** — usable only from the thread that created it,
  enforced by a runtime check on every access.
- **Shared arena** — usable from any thread, with its own per-access
  bookkeeping.
- **Boundary crossing** — copying data from off-heap to heap memory (or
  the reverse) to satisfy an API that only accepts the other kind.

## Assumptions and scope

- Every variant sums the identical deterministic checksum over the
  identical value stream regardless of storage location (java.md,
  rust.md); ns/access, ns/record and copy bytes are measured, never
  correctness-checked, because they are not deterministic quantities.
- `copiedBoundaryCrossing`'s copy is unconditional and whole-dataset on
  every operation, by design — this is what makes the trap visible
  rather than an optimization opportunity this lab missed.
- Rust's variants are honestly reframed rather than force-mapped onto
  Java's arena vocabulary; that reframing (and why) is documented
  explicitly in rust.md, not left implicit.

## Pre-lab diagnostic — answer

The most likely cause is exactly this lab's "off-heap as automatic
speedup" trap in reverse: the one-line `toArray()` conversion silently
copies the ENTIRE segment into a fresh heap array every single time the
reporting job runs, regardless of how much of that array the report
actually reads afterward — this lab's `copiedBoundaryCrossing` variant
demonstrates precisely this shape, and its own measured evidence shows
this copy can dominate total cost by two orders of magnitude when only a
small fraction of the copied data is used. The fix is not "avoid
off-heap" — it is to either copy only the fields the report actually
needs, or restructure the report to read directly from the segment via
`MemorySegment.get` calls rather than materializing a full heap copy
first.
