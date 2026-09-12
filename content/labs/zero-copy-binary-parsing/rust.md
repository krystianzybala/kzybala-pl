# Zero-copy binary parsing and views — Rust track

Crate `zero_copy_binary_parsing_lab`: `fixed_header` / `nested_repeated`
/ `utf8_field` modules, each providing wire-format encoding and five
decoder variants operating on a borrowed `&[u8]` wire buffer.

## A borrowed slice already IS the view — no flyweight type to build

- **`validated_zero_copy_view` reads directly from `&[u8]` via
  `from_be_bytes` — there is no separate "view type" to construct.**
  Unlike Java, where a `MemorySegment`-backed reader is a distinct
  object the code builds and passes around, Rust's ordinary borrowed
  slice reference already has every property a zero-copy view needs:
  bounds-checked indexing, no ownership of the underlying bytes, and a
  lifetime tied to its source. This lab's Rust track needed zero new
  abstraction to get zero-copy access — it is the DEFAULT way to read a
  `&[u8]` in Rust, not a special technique.
- **`mutable_in_place_update` needs `&mut [u8]` specifically, and the
  borrow checker enforces exclusivity.** You cannot hold a
  `validated_zero_copy_view`-style read over the same bytes while also
  calling `mutable_in_place_update` on them — the compiler rejects it,
  at compile time, before the program runs. This is the concrete,
  structural answer to this lab's "returning views past buffer lifetime"
  trap: Java's `MemorySegment` throws at RUNTIME if a confined arena
  closes while a view is still alive; Rust's equivalent class of bug
  simply does not compile.
- **`object_building_decoder`'s `Vec<Box<u64>>` is Rust's honest analog
  of Java's boxed `List<Long>`.** Individually heap-boxing every decoded
  value is not idiomatic Rust — nobody would write this in real code —
  but it is the correct, honest analog of what Java's object-building
  decoder does structurally, and this crate's own measured evidence
  shows it costing dramatically more than the flat-array copy it is
  built on, exactly like the Java track (java.md).
- **This dataset's UTF-8 validation uses `std::str::from_utf8` — the
  standard library's own, real validity check, never skipped or
  replaced with an `unsafe` shortcut.** `validated_zero_copy_view` and
  `lazy_field_decode` both call it and then discard the resulting `&str`
  immediately, reading raw bytes for the actual checksum — validated,
  but never materialized as an owned `String`.

## What was actually measured (dev-only, never published)

Running the evidence binary across all five `fixedHeaderPlusVariablePayload`
variants on this repository's development machine (release build)
confirmed the same qualitative pattern as Java, at smaller absolute
magnitudes: `validated_zero_copy_view` (≈2,023,896 ns, ≈10.12
ns/message) and `lazy_field_decode` (≈2,023,368 ns, ≈10.12 ns/message)
were the fastest and nearly identical to each other;
`copying_decoder` (≈6,526,150 ns, ≈32.63 ns/message) was ≈3.2× slower;
`object_building_decoder` (≈50,414,010 ns, ≈252.07 ns/message) was the
clear outlier at ≈24.9× slower than the view — the individually-boxed
`u64`s costing far more than the flat-array copy, exactly like Java's
finding. `mutable_in_place_update` (≈2,034,457 ns, ≈10.17 ns/message)
cost a small, real premium over the read-only view, consistent with the
extra write/verify/restore round trip. Running `utf8Field`'s evidence
showed the SAME pattern for text specifically:
`validated_zero_copy_view` (≈4,034,907 ns, ≈26.90 ns/message) versus
`copying_decoder` (≈25,388,351 ns, ≈169.26 ns/message) — a real ≈6.3×
gap directly attributable to `String::from_utf8`'s allocation and copy,
confirming the "claiming zero-copy while converting strings" trap's
cost is real and measurable in Rust too, not just theoretical. None of
these exact numbers are published evidence; the directions and
mechanisms are what benchmark.md's real, reproduced evidence is checked
against.

## Build, correctness gate, run

```bash
cd content/labs/zero-copy-binary-parsing/code/rust

# correctness gate — every variant sums to the identical total per dataset;
# mutableInPlaceUpdate's buffer is asserted unchanged after its round trip
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (five variants, fixedHeaderPlusVariablePayload)
cargo bench

# THE evidence tool — run directly, any variant/dataset
cargo run --release --bin zero_copy_binary_parsing_evidence -- \
  --variant validatedZeroCopyView --dataset utf8Field
```

## Cross-language parity notes

- Rust needed no new type to express "zero-copy view" — a borrowed
  `&[u8]` already is one. This is a genuine, disclosed structural
  difference from Java, not a claim that Rust is "better" at this
  technique; Java's `MemorySegment` API exists specifically to make the
  SAME property available to a language whose default reference types
  (`Object[]`, `String`) do not have it.
  `object_building_decoder`'s `Vec<Box<u64>>` is intentionally
  unidiomatic — included only to keep this lab's five-variant matrix
  structurally parallel to Java's, documented as such rather than
  presented as a real-world Rust pattern.
- No cross-harness ranking is published between the JMH benchmarks and
  `zero_copy_binary_parsing_evidence`; the native-Linux evidence runner
  treats Java as the measured side for this lab's publication numbers
  (benchmark.md), and Rust's runs are a separately disclosed instrument.
