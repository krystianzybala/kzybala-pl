# Struct layout, alignment and padding — exercises

## Exercise 1 (diagnosis): the reorder that changed nothing

A developer benchmarks a hot `OrderEvent` Java class before and after
manually reordering its fields (moving a frequently-read `long` field
to be declared first), expecting a throughput improvement from better
read locality. The benchmark shows no measurable difference — not "a
small improvement," genuinely nothing, within noise. Using this lab's
mechanism, name the single most likely explanation, and the one tool you
would run BEFORE concluding anything about the class's actual
performance characteristics.

**Success criteria:** you identify that a plain JVM object's field order
is not guaranteed to be respected by the JVM's own layout algorithm
(this lab's central JOL finding, java.md) — meaning the "before" and
"after" source files may have compiled to the IDENTICAL physical object
layout, in which case "no measurable difference" is not evidence about
cache locality at all, it is confirmation the source change never
reached memory; you name JOL's `ClassLayout.parseInstance()` as the tool
to run on both versions FIRST, before drawing any performance
conclusion from the benchmark numbers.

<details>
<summary>Hint</summary>

This lab's own `ProducerConsumerCounters.PoorFieldOrder` and
`OptimizedFieldOrder` declare their fields in different orders in
source. Did JOL confirm those source orders were respected?
</details>

## Exercise 2 (implementation): find the real footprint floor

Using this lab's Java `HeaderPayloadLayout`, add a SIXTH ordering that
tries to beat the 72-byte floor `natural` and `optimizedFieldOrder` both
hit (theory.md explains why 72 is structurally hard to beat with THIS
field set: 6 bytes of header fields before an 8-byte-aligned 64-byte
payload). Try splitting the 4-byte `sequence` field's role differently,
or reconsider whether `msgType`/`msgFlags` could be combined into a
single 2-byte field instead of two 1-byte fields (a real, legitimate
layout technique — combining adjacent small fields via bit-packing).
Compute, by hand, whether your new field mix CAN reach exactly 64 or 70
bytes, and only then implement and verify it (dev machine, wiring-only —
do not publish these numbers).

**Success criteria:** you correctly identify that combining
`msgType`(1B)+`msgFlags`(1B) into a single packed 2-byte field (or
otherwise reducing the header to exactly 0 or 8 bytes before the
payload) is the only way to beat 72 bytes with this dataset's logical
information content, since the payload's 8-byte alignment requirement is
fixed; you implement it correctly (checksum must still decompose the
combined field back into the original `msgType`/`msgFlags` values before
summing, matching the fixture's checksum definition exactly); and you
verify the resulting `strideBytes()` against your hand computation
before trusting it.

<details>
<summary>Hint</summary>

The payload's own alignment requirement (8 bytes) is fixed and cannot be
changed by reordering the header fields — theory.md states this
explicitly. What CAN change is how many bytes the header itself
consumes.
</details>

<details>
<summary>Solution</summary>

Bit-packing `msgType`(needs only 3 bits, since its fixture range is
0..8) and `msgFlags`(needs 8 bits, range 0..256) into a single 16-bit
field (`(msgType << 8) | msgFlags`, or similar) reduces the header to
exactly `sequence`(4B) + `combined`(2B) = 6 bytes — still not a multiple
of 8, so this alone does NOT beat 72 bytes. The only way to actually
reach a smaller total is to shrink `sequence` itself (if its real-world
range permits a `u16`/`short`) or accept that with a 4-byte `sequence`
field present, 72 bytes is the genuine floor for this exact information
content — a valid, useful, and slightly humbling finding: not every
layout has room for the improvement a learner might expect, and
discovering that via hand computation BEFORE implementing (as this
exercise requires) is the actual skill being tested.
</details>

## Exercise 3 (evidence interpretation): read the JOL offsets, not the source

Below is the shape of one run's JOL output for a hypothetical sixth
counter variant in this lab's family (illustrative structure, not real
captured evidence):

```text
=== producerConsumerCounters.Hypothetical (org.openjdk.jol.info.ClassLayout) ===
OFF  SZ   TYPE DESCRIPTION                      VALUE
  0   8        (object header: mark)
  8   4        (object header: class)
 12   4    int Hypothetical.coldA
 16   8   long Hypothetical.producerCount
 24   4    int Hypothetical.coldB
 28   4        (alignment/padding gap)
 32   8   long Hypothetical.consumerCount
Instance size: 40 bytes
```

The class was declared in source as:
`producerCount, coldA, consumerCount, coldB`. Answer from this block
alone: (a) did the JVM respect the declared order; (b) are
`producerCount` and `consumerCount` on the same 64-byte cache line on a
typical host; (c) name the one change (to source OR to the class
itself) that would reliably move `consumerCount` onto a different line,
per this lab's own findings.

**Success criteria:** (a) no — the JVM placed `coldA` before
`producerCount` despite `producerCount` being declared first, consistent
with this lab's own `PoorFieldOrder`/`OptimizedFieldOrder` finding
(java.md); (b) yes — `producerCount`@16 and `consumerCount`@32 are only
16 bytes apart, comfortably within any single 64-byte line on essentially
every current host; (c) reordering source fields again would NOT
reliably fix it (per (a)'s own evidence) — only explicit, retained
padding fields (this lab's `CacheLineAligned` technique: enough unused
`long` fields between the two counters to force a real 64+ byte gap) or
an equivalent alignment annotation would reliably separate them, because
padding fields cannot be "optimized away" the way empty reordering can
be ignored.

<details>
<summary>Hint</summary>

This lab's own `PoorFieldOrder`/`OptimizedFieldOrder` classes are
proof, not theory, that reordering source fields doesn't reliably change
JVM object layout. What technique in this lab's own matrix DID reliably
work?
</details>
