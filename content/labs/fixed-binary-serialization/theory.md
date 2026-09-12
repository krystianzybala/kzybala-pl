# Object serialization vs fixed binary layout — theory

## Performance question and hypothesis

**Question:** how much latency and allocation are spent turning in-memory
state into bytes and back?

**Hypothesis:** schema-driven fixed layouts and flyweights reduce
allocation and copies compared with generic object serialization, at the
cost of evolution discipline.

**What would disprove it:** if the fixed-layout variants allocated as much
as the generic codec per encode/decode (the layout wouldn't actually be
removing anything), or if a flyweight view's per-field access cost more
than materializing the whole record up front (deferred decoding would be
pure overhead, not a saving), or if the generic codec's wire bytes were
*smaller* than the fixed layout's for every dataset (fixed layouts would
have no size advantage to justify the lost flexibility) — any of these
would mean the mechanism this lab teaches isn't actually doing what it
claims.

## Learning objective

Show that "serialization cost" is really three separable things — a
schema-discovery cost (does the format have to describe its own shape on
every message, or is the shape agreed out of band?), a copy cost (does
decoding require allocating a new object graph, or can a caller read
fields directly out of the received bytes?), and a validation cost (which
this lab keeps constant across variants so it never gets credited to the
wrong mechanism) — and that a fixed binary layout removes the first two by
construction, at the price of having to handle schema evolution
explicitly instead of automatically.

## Prerequisites

- The [Struct Layout, Alignment and Padding](/lab/struct-layout-alignment/)
  lab — a fixed wire layout is the same fixed-offset-per-field idea as an
  in-memory struct layout, just applied to a byte stream instead of a heap
  object.

### Pre-lab diagnostic

Before reading further: a team benchmarks "JSON vs binary" and reports
binary is 5x faster to decode. A colleague objects that this isn't a fair
comparison. What's the most likely thing the binary format is skipping
that JSON's decoder can't skip — and is it something the binary format
gets to skip for free, or something it moved somewhere else?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Fixed binary layout | A wire format where every field lives at a known, constant byte offset — the reader never has to read length prefixes or type tags to know where the next field starts. |
| Flyweight (view) | An object that holds a reference to raw bytes and computes each field on access, instead of copying every field into new fields of its own at decode time. |
| Schema-driven | The message shape is agreed between reader and writer ahead of time (in code, in this lab), rather than being self-describing on the wire. |
| Generic object codec | A serializer that walks an object's runtime shape reflectively and writes a self-describing representation — flexible, but the shape description itself costs bytes and CPU on every message. |
| Wire compatibility | Two independently-written codecs producing (or accepting) byte-for-byte identical output for the same logical message — required here between the ByteBuffer and FFM variants. |
| Version evolution | Adding or removing fields across schema versions without breaking readers built against an older or newer version. |

## Three costs, not one

It is tempting to treat "encode/decode speed" as a single number, but this
lab's variants separate three genuinely different costs:

1. **Schema-discovery cost** — does the wire format have to carry
   information about its own shape (a class name, a field count, a length
   prefix)? A generic object codec re-derives and re-writes that shape on
   every single message; a fixed layout agrees the shape once, in code
   compiled into both endpoints, and pays nothing for it at message time.
2. **Copy/allocation cost** — does turning wire bytes back into something
   usable require allocating a new object graph (arrays, boxed fields,
   nested objects), or can a caller read a field straight out of the
   received buffer? This is the cost a flyweight view specifically
   removes: it defers materialization until (and unless) the caller
   actually asks for the full object.
3. **Validation cost** — checking that a field is in range, correctly
   typed, or internally consistent. This lab holds validation constant
   across variants (the correctness fixture is asserted identically for
   all of them) specifically so a variant can never look artificially fast
   by silently skipping it — see "excluding validation" in the traps
   below.

## The fixed layout in this lab

Every variant in this lab encodes/decodes the same logical message shape —
one record with a version byte, an id, a device id, an opcode, flags, a
floating-point value, a timestamp, a fixed-size array of samples, and one
optional field — at one fixed 70-byte little-endian offset table (see
`java.md`/`rust.md` for the exact offsets). The four dataset profiles
(small command, medium event, repeated fields, versioned optional field)
are simply four different populations of that same shape, not four
different wire formats: a small command mostly leaves fields at their zero
default, a medium event populates id/deviceId/value/timestamp, repeated
fields fills every sample slot, and the versioned-optional-field profile
sets the optional slot's presence flag and value.

Capping the sample array at a fixed maximum (rather than letting it grow
to an arbitrary length) is what keeps the layout *fixed*: a flyweight view
needs every field's offset to be knowable without reading the data first,
which a genuinely variable-length field would break.

## Version evolution, explicitly

A fixed layout cannot silently add a field the way a self-describing
format can — every reader has an offset table baked into its code. This
lab's answer is to reserve every field's slot up front, whether or not a
given message uses it, and to signal "is this optional field meaningful
here?" with an explicit presence byte rather than by making the message
shorter. A reader built against an older schema version that doesn't know
about the optional field can still read the fixed slot (it just ignores
what's there); a reader that does know about it checks the presence byte
before trusting the value. This is "support version evolution explicitly"
from this lab's learning outcomes: the format never silently means two
different things depending on which version wrote it.

## Visualization 1: wire layout explorer (textual fallback)

```
Offset  Bytes  Field             Small command   Medium event    Repeated fields   Versioned optional
0       1      version           1               1               1                 2
1       8      id                42              1000000007      99                555
9       4      deviceId          0               314159          5                 8
13      1      opcode            7               2               3                 9
14      1      flags             1               0               0                 4
15      8      value (f64 bits)  0.0             98.6            0.0               0.0
23      8      timestamp         0               1731000000000   0                 0
31      2      sampleCount       0               0               8                 0
33      32     samples[8] (i32)  all zero        all zero        10,-20,30,...,-80 all zero
65      1      optionalPresent   0               0               0                 1
66      4      optionalField     0               0               0                 123456
```

Every profile is exactly 70 bytes on the wire — the layout's size never
changes with the data, only the *meaning* of already-reserved slots does.

## Visualization 2: encode/decode pipeline (textual fallback)

```
Generic object codec:
  encode: object -> reflect over fields -> write class descriptor -> write field values -> bytes
  decode: bytes -> read class descriptor -> resolve class -> allocate object -> reflect-set fields
                                             ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ schema-discovery cost, paid every message

ByteBuffer/manual codec:
  encode: object -> write each field at its known offset -> bytes (no shape description)
  decode: bytes -> read each field at its known offset -> allocate ONE result object

FFM flyweight codec:
  encode: object -> write each field at its known offset -> bytes (identical bytes to ByteBuffer)
  decode: bytes -> wrap in a view -> read fields ON DEMAND, no object allocated until asked
```

## Visualization 3: size/latency trade-off (textual fallback)

Because the generic codec's wire size scales with class-descriptor
overhead (roughly constant per message, dominated by the class metadata
rather than the payload) while the fixed layout's wire size is exactly 70
bytes regardless of profile, the *relative* size gap is largest for the
smallest message (small command) and smallest for the largest one
(repeated fields, which already carries real payload weight in the fixed
layout too). Real numbers are in `benchmark.md`, not fabricated here.

## Known traps

- **Comparing incompatible schemas.** A "faster" format that dropped a
  field, changed its precision, or stopped validating input is not a fair
  comparison — see the Semantic Equivalence Contract in every variant's
  test suite.
- **Excluding validation.** Skipping bounds/range checks to make decode
  look cheaper attributes someone else's cost to the wrong mechanism; this
  lab's correctness fixture is asserted identically across every variant.
- **Using compressed generic formats without reporting CPU.** Compression
  can shrink wire bytes while *increasing* CPU cost — reporting only wire
  size after compression, without also reporting the CPU spent producing
  it, hides where the cost actually moved.
- **Hardcoding endianness silently.** Every variant in this lab pins
  little-endian explicitly (`ByteOrder.LITTLE_ENDIAN` in Java,
  `to_le_bytes`/`from_le_bytes` in Rust) — the trap is not picking an
  order, it's leaving it implicit so a future reader can't tell which
  order was assumed.

## Assumptions and scope

- The message shape's sample array is capped at a fixed maximum
  (`MAX_SAMPLES = 8`) specifically so the layout stays fixed-offset; a
  genuinely unbounded repeated field would require a different mechanism
  (length-prefixed framing) outside this lab's scope.
- Correctness (round-trip equality, byte-for-byte wire compatibility
  between the two fixed-layout variants) is verified by a fixture shared
  identically by both languages' test suites (see java.md, rust.md)
  before any timing is trusted.
- The generic object codec here means JDK's built-in
  `ObjectOutputStream`/`ObjectInputStream` specifically — the one generic
  serializer already available in this repository without adding a new
  dependency, not a claim about every possible generic serialization
  library.
- This lab measures single-message encode/decode cost, not batching,
  streaming framing, or network I/O — see the
  [Memory-Mapped Files](/lab/memory-mapped-files/) lab for what happens
  once these fixed records are read directly out of a mapped region
  instead of a byte array.

## Pre-lab diagnostic — answer

The binary format is almost certainly skipping **schema description** —
JSON's decoder has to read field names as text on every message ("id",
"deviceId", ...) and match them against expected fields, while a binary
format with a fixed, out-of-band-agreed schema never writes or reads names
at all. This is real and legitimate savings *for that specific cost*, but
it didn't disappear for free: the schema agreement moved from "on the
wire, on every message" to "in the code both sides run," which is exactly
this lab's "cost of evolution discipline" — the binary format can no
longer evolve its shape without redeploying both readers and writers in
lockstep, where JSON's self-describing fields tolerate some drift for
free. The fair comparison names *which* cost moved where, rather than
declaring one format universally faster.
