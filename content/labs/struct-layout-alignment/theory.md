# Struct layout, alignment and padding — theory

## Performance question and hypothesis

**Question:** how do field order, alignment and cache-line boundaries
affect footprint and access cost?

**Hypothesis:** reordering fields and separating contended values can
reduce padding or coherence traffic, while packed layouts may create
unaligned access penalties.

**What would disprove it:** if reordering a struct's fields never
changed its footprint, or if separating two contended fields by a full
cache line never changed multi-threaded throughput, the premise that
layout is a controllable performance lever would be wrong. Every variant
in this lab reads the identical value stream and reproduces the
identical checksum (or, for the concurrent-counter dataset, the
identical final counts); only field order and alignment change, and this
lab's real evidence (JOL/JMH/Criterion-measured, never estimated) is
checked against that.

## Learning objective

Construct explicit byte layouts by hand, reason correctly about
alignment inside both single structs and arrays of structs, and avoid
undefined behavior when working with packed, potentially-unaligned data.

## Prerequisites

- The [Allocation cost and object layout](/lab/allocation-object-layout/)
  lab (bytes/record and the header-overhead mechanism this lab builds on
  directly).
- The [Heap vs off-heap with FFM MemorySegment](/lab/ffm-memory-segments/)
  lab (the offset-based `MemorySegment` access technique this lab's
  Java track reuses for every sequential-access dataset).

## Pre-lab diagnostic

A developer reorders a hot Java class's fields, moving the two most
frequently-read fields to be declared first, expecting better cache
locality. They benchmark before and after and see no measurable
difference. A teammate says "that's expected — you didn't add
`@Contended` or padding." Using this lab's mechanism, are they right,
and what would you check FIRST before accepting that explanation?

(Answer at the end of this page.)

## The mechanism: two languages, two very different defaults

- **The JVM does not respect field declaration order — ever, for
  ordinary objects.** This is not a subtle edge case; this lab's own
  JOL evidence shows it directly: a class with a `long` field declared
  FIRST in source can still be laid out by HotSpot with an unrelated
  `int` field placed BEFORE it, because the JVM's default object-layout
  algorithm groups fields by size/type for its own packing efficiency,
  independent of what order the programmer wrote them in. Reordering
  fields in a plain Java class's source is, for footprint and coherence
  purposes, a no-op — the JVM's algorithm decides, not you.
- **Rust's DEFAULT struct layout is likewise unspecified — the compiler
  is free to reorder.** A plain `struct Foo { ... }` with no `repr`
  attribute in Rust has exactly the same property: the compiler may (and
  often does) reorder fields for tighter packing than the declared
  order would produce, regardless of what the programmer wrote. This
  lab's own measured evidence shows Rust's default-layout struct landing
  at the theoretical minimum size automatically, with zero manual
  reordering (java.md, rust.md) — a genuinely different DEFAULT outcome
  from the JVM's (which does not guarantee minimal size, only its own
  internal packing rule), even though both share the "declaration order
  is not the layout" property.
- **`#[repr(C)]` (Rust) and `MemoryLayout.structLayout` (Java FFM) are
  where declaration order becomes real.** Both give the programmer
  EXACT control: fields are placed in the given order, with only the
  padding the platform's natural-alignment rules require between them
  (each field at the next offset that is a multiple of its own size).
  This is the ONLY place in either language where "reorder your fields"
  is a real, applicable technique — for a plain JVM object or a
  default-repr Rust struct, it does nothing.
- **A record's minimum footprint is bounded by its worst-fitting field
  mix, not just its total logical bytes.** Some field-size combinations
  cannot reach zero padding no matter how they are ordered — this lab's
  `headerPlusPayload` dataset demonstrates this directly: its
  `natural` and `optimizedFieldOrder` layouts land at the IDENTICAL
  total size (72 bytes), because 6 bytes of small header fields can
  never perfectly fill a multiple of 8 before the 64-byte payload's own
  alignment requirement kicks in — reordering only moves WHERE the
  unavoidable padding sits, not whether it exists.
- **Explicit cache-line alignment is a different tool from field
  reordering, solving a different problem.** Padding a record (or a
  single hot field) so it starts at, and fully occupies, its own cache
  line prevents two independently-written fields from ever sharing a
  line — this is what actually eliminates false-sharing coherence
  traffic. Reordering fields to sit "closer together" for read locality
  is a legitimate, separate goal that can, if anything, make coherence
  WORSE for fields written by different threads (theory this lab's
  `producerConsumerCounters` dataset tests directly).
- **A packed, unaligned field is not automatically undefined behavior —
  but taking its address usually is.** Both languages provide safe ways
  to READ a value from a misaligned location (`ValueLayout`'s
  `*_UNALIGNED` variants in Java; a by-value field read from a
  `#[repr(C, packed)]` struct in Rust) — the danger this lab's "unsafe
  references to packed fields" trap names is specifically forming a
  reference/pointer to a misaligned field and treating it as if it were
  ordinarily aligned, which several real CPU architectures reject or
  penalize at the hardware level.

## Visualization 1: offset map (deterministic)

The `mixedPrimitiveRecord` dataset's six fields, laid out by this lab's
five variants — not a measurement, the exact byte offsets this lab's
Java (`MemorySegment`, order always respected) and Rust (`#[repr(C)]`,
order always respected) implementations compute and verify against each
other:

| Variant | Byte layout | Total |
|---|---|---|
| poorFieldOrder | active@0,[pad7],timestamp@8,category@16,[pad7],amountTicks@24,flags@32,[pad2],quantity@36 | 40B |
| optimizedFieldOrder | amountTicks@0,timestamp@8,quantity@16,flags@20,active@22,category@23 | 24B, zero padding |
| cacheLineAligned | (optimized layout)@0..24, [pad 40] | 64B (one full cache line) |
| packedUnaligned | timestamp@0,active@8,category@9,amountTicks@10(unaligned!),quantity@18(unaligned!),flags@22 | 24B, zero padding, two unaligned fields |

## Visualization 2: cache-line overlay (illustrative pattern)

How `producerConsumerCounters`' two hot fields sit relative to a 64-byte
cache line across variants — **illustrative of the general shape, not
extracted from a live run of this lab's code**; the real evidence is
this lab's own JOL/JMH output (java.md):

```text
natural / poorFieldOrder / optimizedFieldOrder:
  [line 0: header | producerCount | consumerCount | ...]   <- BOTH on one line, regardless of source order

cacheLineAligned:
  [line 0: producerCount | padding...............]
  [line 1: consumerCount | padding...............]         <- guaranteed separate lines
```

## Visualization 3: footprint vs access chart (conceptual model)

The trade-off this lab's hypothesis names explicitly — smaller is not
always faster to read:

| Variant | Footprint | Expected access cost driver |
|---|---|---|
| optimizedFieldOrder | smallest (no padding) | fastest for a plain sequential scan |
| cacheLineAligned | largest per record | slower per-byte scan, but immune to false sharing under concurrent access |
| packedUnaligned | smallest, but unaligned | correct and safe if read by value, but not guaranteed faster |

Textual fallback for all three visualizations: neither language respects
source field order by default; explicit layout control
(`MemoryLayout.structLayout` / `#[repr(C)]`) is required to make
reordering do anything at all; and cache-line alignment solves
coherence, not footprint — the two are separate levers with separate
costs.

## Terminology

- **Natural alignment** — the rule that a value of size N bytes is
  placed at a byte offset that is a multiple of N (or of the platform's
  chosen alignment for that type).
- **Padding** — unused bytes a layout inserts to satisfy alignment,
  either between fields or after the last field (trailing padding, to
  make array elements uniformly aligned).
- **False sharing** — two independently-written fields sharing one
  cache line, causing writes on either core to invalidate the other's
  cached copy even though neither thread touches the other's data
  (content/labs/false-sharing, this lab's direct mechanism ancestor for
  `producerConsumerCounters`).
- **Unaligned access** — reading/writing a value at an offset that is
  not a multiple of its own size; supported explicitly by both
  languages' safe APIs, at a potential (host-dependent) performance
  cost.

## Assumptions and scope

- Every sequential-access variant sums the identical deterministic
  checksum over the identical value stream regardless of layout
  (java.md, rust.md); `producerConsumerCounters`' oracle is the exact
  final count pair after a fixed number of increments per thread, not a
  checksum.
- This lab's Java `#[repr(C)]`-equivalent (`MemoryLayout.structLayout`)
  and Rust's `#[repr(C)]` byte layouts are verified to match EXACTLY,
  offset for offset, for every sequential-access variant — the
  cross-language equivalence contract for this lab is bytes, not just
  checksums.
- 64 bytes is this lab's assumed cache-line size, stated explicitly per
  this lab's "using 64-byte lines as universal without documenting host"
  trap — the actual value must be confirmed against the measured
  native-Linux host, never assumed portable (benchmark.md).

## Pre-lab diagnostic — answer

The teammate is very possibly right that padding/`@Contended` is needed
for a MULTI-THREADED coherence problem, but the FIRST thing to check is
whether the "no measurable difference" result is actually evidence of
anything at all: for a SINGLE-THREADED read-locality claim, plain field
reordering in an ordinary Java class does nothing, because (this lab's
own JOL evidence shows directly) the JVM does not respect declaration
order in the first place — the "before" and "after" source files may
well have compiled to the IDENTICAL object layout, in which case "no
measurable difference" is not a null result about cache locality at
all, it is confirmation that the change never reached the actual memory
layout. The correct diagnostic step is to run JOL's `ClassLayout` on
both versions BEFORE concluding anything about performance.
