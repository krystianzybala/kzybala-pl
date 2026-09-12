# Zero-copy binary parsing and views — theory

## Performance question and hypothesis

**Question:** how can a decoder expose typed fields without copying
payloads or allocating object graphs?

**Hypothesis:** views over validated immutable bytes can eliminate
copies and allocations, but lifetime, endian, bounds and mutation rules
must be explicit.

**What would disprove it:** if a validated zero-copy view were no
faster and no less allocation-heavy than a decoder that copies and
materializes a full object graph, the premise that views are a genuine,
measurable technique — not just a theoretical one — would be wrong.
Every decoder in this lab reads the identical big-endian wire bytes and
reproduces the identical checksum; only how much is copied, allocated
and validated, and when, differs, and this lab's real evidence
(JMH/Criterion-measured, including actual allocation-rate evidence via
`-prof gc`, never estimated) is checked against that.

## Learning objective

Implement a view type whose lifetime is provably bounded by its backing
buffer, separate validation (must happen, exactly once, before any field
is trusted) from access (may happen zero, one or many times), and
measure — never assume — how many bytes a given decoding strategy
actually copies.

## Prerequisites

- The [Heap vs off-heap with FFM MemorySegment](/lab/ffm-memory-segments/)
  lab — this lab's Java `MemorySegment`-backed views and its
  `ValueLayout` endianness handling build directly on that lab's
  mechanism.
- The [Struct layout, alignment and padding](/lab/struct-layout-alignment/)
  lab — this lab's wire format needed the SAME unaligned-access
  discovery that lab made explicit: a byte-packed protocol header is
  essentially never naturally aligned (java.md's own real finding while
  building this lab).

## Pre-lab diagnostic

A team's message decoder is described in its documentation as
"zero-copy." A profiler run against it shows real, nonzero allocation
proportional to the number of string fields decoded. Using this lab's
mechanism, name the single most likely place the "zero-copy" claim
actually broke down, and which of this lab's named traps it matches.

(Answer at the end of this page.)

## The mechanism: a view is a promise, and every part of the promise must be kept

- **A view is a bounds-checked pointer into someone else's memory, not a
  copy.** Java's `MemorySegment` slice and Rust's borrowed `&[u8]` both
  give you typed, bounds-respecting access to bytes you do not own — no
  allocation, no copy, as long as you only ever read through the view.
  This lab's own measured evidence shows this claim is not
  aspirational: a validated zero-copy view's allocation rate, measured
  directly, lands within a few dozen bytes of literally zero (java.md,
  rust.md).
- **Validation must happen exactly once, and it must actually run.**
  "Zero-copy" is not "zero-validation" — bounds and, for text fields,
  UTF-8 validity must be checked before any field is trusted, or the
  view is not safe, it is merely fast. This lab's "skipping validation"
  and "using invalid UTF-8 unchecked" traps name exactly the shortcut
  that would make a view unsafe rather than efficient.
- **"Claiming zero-copy while converting strings" is the single most
  common way this technique quietly fails.** Calling `new String(...)`
  (Java) or building an owned `String` (Rust) from view bytes is a real,
  full copy and decode — completely legitimate when you actually need an
  owned string, but it silently defeats the entire point if done inside
  what is advertised as the zero-copy path. This lab's
  `validatedZeroCopyView`/`validated_zero_copy_view` never materializes a
  string at all for its checksum operation; `copyingDecoder` always
  does — the two are never allowed to blur together.
- **Object-building decodes usually cost more than the raw copy they are
  built on.** Wrapping each decoded value in its own object (Java's
  boxed `List<Long>`, Rust's `Vec<Box<u64>>`) adds a real allocation PER
  VALUE on top of whatever the underlying copy already cost — this lab's
  own measured evidence shows this as the single most expensive variant
  in its matrix, by a wide margin, in both languages.
- **In-place mutation needs an explicit, different contract from a
  read-only view.** A view that permits writes is not simply "a view
  that happens to also write" — it requires the caller to reason about
  who else might be reading the same bytes at the same time. This lab's
  `mutableInPlaceUpdate` variant makes the contract explicit by
  round-tripping a value (write, verify, restore) so its correctness
  oracle stays identical to the read-only variants', while still
  exercising a genuine, measurable write path.
- **A dangling view is a lifetime bug, and the two languages catch it
  completely differently.** Java's `MemorySegment` throws at RUNTIME if
  you touch a segment after its confined arena closes; Rust's borrow
  checker rejects a dangling `&[u8]` at COMPILE time, before the program
  ever runs. This lab's "returning views past buffer lifetime" trap has
  a real, structurally different answer in each language (rust.md).

## Visualization 1: frame/view lifetime diagram (deterministic)

Every message in this lab's wire format, and what backs a view over it —
not a measurement, the exact contract this lab's implementation follows:

| Component | Bytes | Backs |
|---|---|---|
| `msgType` | 1B | header |
| `seq` | 4B, big-endian | header |
| length/count field | 4B, big-endian | header |
| payload | variable, dataset-specific | the view's actual data |

A view is valid for exactly as long as the backing wire buffer is; this
lab never returns a view whose backing buffer could be freed or mutated
by someone else while the view is still in use.

## Visualization 2: copy counter (illustrative pattern)

Where each variant's bytes actually go — **illustrative of the general
shape, not extracted from a live run of this lab's code**; the real
evidence is this lab's own `-prof gc` output (java.md, benchmark.md):

```text
copyingDecoder:          wire bytes -> [COPY] -> fresh array/String -> read
objectBuildingDecoder:   wire bytes -> [COPY] -> fresh array/String -> [WRAP] -> N objects -> read
validatedZeroCopyView:   wire bytes -> [VALIDATE once] -> read directly, no copy
```

## Visualization 3: decoder pipeline (conceptual model)

The four stages every decoder in this lab passes through, and which
ones each variant actually performs:

| Stage | copyingDecoder | objectBuildingDecoder | validatedZeroCopyView | lazyFieldDecode |
|---|---|---|---|---|
| Validate bounds/UTF-8 | yes, upfront | yes, upfront | yes, upfront | yes, deferred to first access |
| Copy bytes | yes | yes | no | no |
| Materialize objects | no (raw arrays) | yes (per-value) | no | no |
| Read fields | from the copy | from the objects | from the view | from the view |

Textual fallback for all three visualizations: a view only pays for
validation, never for copying or per-value object materialization; every
other variant in this lab pays progressively more, and this lab's real
evidence shows exactly how much.

## Terminology

- **View / flyweight reader** — a typed accessor over borrowed bytes
  with no independent storage of its own.
- **Validation** — the one-time check (bounds, UTF-8 validity) that
  makes trusting a view's fields safe.
- **Zero-copy** — a claim about bytes, not about validation or
  computation: a zero-copy decoder still validates, still computes, it
  simply never duplicates the payload bytes into new storage.

## Assumptions and scope

- Every variant sums the identical deterministic checksum over the
  identical wire bytes regardless of decoding strategy (java.md,
  rust.md); ns/message, B/message, bytes copied and validation cost are
  measured, never correctness-checked, because they are not
  deterministic quantities in the same sense.
- This lab's `utf8Field` dataset generates only printable ASCII text —
  always valid UTF-8 by construction — specifically so its checksum
  (sum of byte values) is identical whether computed from raw bytes or
  decoded chars, which is what makes a genuinely zero-copy
  implementation possible to write and verify honestly for this
  dataset.
- `mutableInPlaceUpdate`'s mutation always round-trips back to the
  original value before the method returns — its checksum is required
  to match the four read-only variants' exactly, which this lab's tests
  verify directly (java.md, rust.md).

## Pre-lab diagnostic — answer

The single most likely place the "zero-copy" claim broke down is
exactly this lab's "claiming zero-copy while converting strings" trap:
somewhere in the decode path, a string field is being materialized as an
owned `String` (Java) or `String` (Rust) — a real allocation and a real
UTF-8 decode — even though the surrounding code is labeled zero-copy.
The nonzero, string-count-proportional allocation the profiler shows is
the direct fingerprint of that materialization; a genuinely zero-copy
decoder's allocation rate for string fields should look like this lab's
own `validatedZeroCopyView` measured evidence — within a few dozen bytes
of zero, not proportional to the number of strings decoded.
