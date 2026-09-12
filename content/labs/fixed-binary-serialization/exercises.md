# Object serialization vs fixed binary layout — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the reader that silently misreads a newer message)

A colleague ships a v2 writer that, for the `versionedOptionalField`
profile, changes `optionalField`'s meaning from "a plain counter" to "an
enum ordinal," but keeps the wire layout — offsets, `WIRE_SIZE`,
`optionalPresent` — completely unchanged. Their v1 reader (built before
this change, unaware of the new meaning) doesn't crash. It also doesn't
report an error. It just occasionally behaves as if a counter had an
absurd value.

**Task:** explain exactly what the v1 reader is doing wrong, and why
neither `WIRE_SIZE` nor the correctness fixture (which only checks
round-trip byte equality) catches this.

**Success criteria:** you can state precisely what information a fixed
layout does *not* carry on the wire, and propose one concrete change to
this lab's format that would let a reader detect "I don't know how to
interpret this field's new meaning" rather than silently misinterpreting
it.

<details>
<summary>Hint</summary>

`version` already exists in this layout. Is it being used for anything
beyond documentation?

</details>

<details>
<summary>Solution</summary>

The wire format carries `version` as a byte, but nothing in this lab's
codecs actually branches on it — every reader interprets `optionalField`
identically regardless of what `version` says. A fixed layout only
guarantees *where* a field is, never *what it means once you're there*;
that meaning lives entirely in the reader's code, and nothing on the wire
forces the two to agree. The round-trip fixture can't catch this because
round-tripping only proves "the bytes I wrote come back out unchanged" —
it says nothing about whether two *different* writers agree on what those
bytes mean.

The concrete fix: make readers actually branch on `version` (reject or
special-case an unrecognized value, rather than reading `optionalField`
unconditionally) — turning `version` from a fixed-layout constant nobody
checks into part of the explicit contract the "version evolution" learning
outcome asks for.

</details>

## Exercise 2 — Implementation (a fourth codec: length-delimited but still allocation-light)

**Task:** implement a new variant, `CompactVarintCodec` (Java) /
`encode_compact`/`decode_compact` (Rust), that varint-encodes `id` and
`timestamp` (most messages in this lab's fixtures don't need the full 8
bytes) while keeping every other field at its current fixed width — a
genuinely mixed fixed/variable layout, unlike every existing variant here.

**Success criteria (measure, don't assert):**

1. Round-trips all four fixture profiles correctly, verified by the
   existing correctness-suite pattern extended to the new variant.
2. Report the wire size for each of the four profiles compared to the
   fixed `WIRE_SIZE=70` — it should be smaller for `smallCommand` (whose
   `id`/`timestamp` are small) and possibly *not* smaller for
   `mediumEvent` (whose `id`/`timestamp` are large enough that varint
   encoding costs more bytes than the fixed 8).
3. Explain, from your own measurement, why this variant can no longer be
   a flyweight — name the specific field-offset property it gives up
   compared to `EventView`.

<details>
<summary>Hint</summary>

A flyweight's whole premise is "every field's offset is knowable without
reading the data first." What does a varint-encoded field require you to
do before you can find the *next* field?

</details>

## Exercise 3 — Evidence interpretation (JFR allocation profile)

Below is a **synthetic teaching example** in JFR's `jfr print
--events jdk.ObjectAllocationSample` textual format — constructed for this
exercise, not captured from any run. It is educational material for
practicing allocation-profile interpretation only: it is never used as
measurement evidence, never supports this lab's performance conclusions,
and never enters a comparison or maturity calculation. (The lab's real
allocation evidence comes exclusively from the native-Linux evidence
runner and is imported with full provenance; see `benchmark.md`.) Two 10
second decode-loop profiles, same message count, same JVM flags, labels
removed:

```
Profile A — allocation events by object type
  byte[]                      1,204 samples   ~9.6 MB
  pl.kzybala.lab.binser.EventRecord   1,198 samples   ~2.1 MB
  (java.lang.Double boxing)             0 samples

Profile B — allocation events by object type
  byte[]                      1,187 samples   ~9.5 MB
  pl.kzybala.lab.binser.EventRecord   1,201 samples   ~2.1 MB
  java.io.ObjectStreamClass$ClassDataSlot   1,190 samples   ~4.3 MB
  java.util.HashMap$Node                    3,402 samples   ~3.9 MB
  (java.lang.Double boxing)                   890 samples   ~0.1 MB
```

**Task:** decide which profile (A or B) is the generic object codec and
which is the ByteBuffer codec, and justify it from the allocation *types*
present — not just the total byte count. Then state one conclusion this
data **cannot** support.

**Success criteria:** your identification is correct, your reasoning names
the specific mechanism each extra allocation type in your chosen profile
comes from (class-descriptor resolution, reflective field caching, boxing
a primitive into an object), and your "cannot conclude" statement is
genuinely unsupported by this data rather than merely cautious.

<details>
<summary>Hint</summary>

Both profiles allocate roughly the same `byte[]`/`EventRecord` volume —
that's the payload every variant has to produce regardless of mechanism.
Look at what's allocated *in addition* to that shared baseline.

</details>

<details>
<summary>Solution</summary>

**Profile B is the generic object codec.**

- `ObjectStreamClass$ClassDataSlot` only exists because
  `ObjectInputStream` has to resolve and cache a class descriptor for
  `SerializableEvent` — the schema-discovery cost `theory.md` describes,
  visible directly as an allocation type that has no reason to exist in a
  fixed-layout decode at all.
- `HashMap$Node` at a much higher sample count than either payload type is
  consistent with `ObjectStreamClass`'s internal field-name-to-slot
  lookup structures being rebuilt or consulted per stream — a cost with no
  analogue in `ByteBufferCodec`, which never looks anything up by name.
- The boxed `Double` samples are consistent with reflective field
  assignment going through `Field.set(Object, Object)`, which must box a
  primitive `double` to pass it as an `Object` — `ByteBufferCodec` calls
  `buf.putDouble(double)` directly, no boxing possible.

Profile A, with none of these three allocation types present at all,
matches `ByteBufferCodec`'s decode: it only ever allocates the
`EventRecord` result and the `byte[]` wire buffer being read.

**What this data cannot support:** any claim about which language,
encoding, or JDK version produces less garbage collector pressure *in
general* — this is one specific decode loop on one specific dataset
profile, and it says nothing about encode cost, about the FFM flyweight
variant (present in neither profile), or about total pause time impact
under a specific GC. It also cannot be used to estimate absolute
throughput — allocation *rate* and allocation *cost* are related but
distinct, and this data shows only rate.

</details>
