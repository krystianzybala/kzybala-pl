# Allocation cost and object layout — Rust track

Crate `allocation_object_layout_lab`: the fixed constants and
deterministic generation shared with the Java side, the four
representation variants as modules (`boxed_object_graph`,
`flat_primitive_arrays`, `packed_off_heap_struct`,
`reused_mutable_holder`), and `allocation_object_layout_evidence` — a
real, pinned-worker evidence binary matching the Java track's
construct/sumHot contract.

## The pieces

- **`boxed_object_graph`** — individually heap-allocated records via
  `Box::new`, the honest analog of Java's "one heap object per record,
  accessed by reference." Rust has no per-object header the way Java
  does: a `Box<T>` is a pointer to a heap-allocated `T` with no mark
  word, no class pointer — this variant is expected to (and does) show
  none of Java's per-instance header tax, isolating reference-indirection
  cost from header cost in a way Java's own variants cannot. `TreeNode`
  is a real recursive struct — `left`/`right` are `Option<Box<TreeNode>>`
  — built with plain recursion (tree height ≈18 for this lab's N, safely
  within the stack) rather than a manual stack.
- **`flat_primitive_arrays`** — parallel `Vec<T>` per field, the same
  struct-of-arrays technique as Java's, with the identical
  index-arithmetic tree traversal for `treeNodes`.
- **`packed_off_heap_struct`** — a raw `Vec<u8>` buffer with manual
  offsets, `to_ne_bytes`/`from_ne_bytes` for every read/write. This is
  structurally parallel to Java's `MemorySegment` technique, kept for
  matrix parity — but it is worth saying plainly: an ordinary
  `Vec<Record>` in Rust is *already* a packed, header-free struct array
  (`flat_primitive_arrays`'s own `Orders`/`Tuples` come close to this,
  field-by-field). Rust does not need an escape hatch from an object
  header the way Java needs `MemorySegment`, because it never had one.
  Every access in this module uses safe byte-slice conversions — no
  `unsafe` anywhere, and no risk of the "using packed unaligned fields
  unsafely" trap, since `to_ne_bytes`/`from_ne_bytes` never require the
  underlying buffer to be aligned.
- **`reused_mutable_holder`** — one instance streamed through the
  source. Worth naming honestly: none of this lab's holder structs
  (`OrderHolder`, a plain `u64`, `TupleHolder`) own any heap memory, so
  "reuse" in Rust does not avoid a heap allocation the way it does in
  Java — Rust's ordinary stack-local values already cost nothing extra
  per iteration. The real, meaningful contrast in Rust is
  `boxed_object_graph` (N real heap allocations) versus everything else
  (zero per-record heap allocation), not a three-way split the way
  Java's GC-relevant allocation story creates one.

## What was actually measured (dev-only, never published)

Running `allocation_object_layout_evidence` across all four variants on
`ordersQuotes` (N = 200,000, release build) on this repository's
development machine produced a genuinely surprising cross-language
result worth stating plainly: `boxedObjectGraph`'s `construct`
(≈21.6 ns/record) was **slower per record than Java's own
`boxedObjectGraph` construct** (≈4.2 ns/record) on the same machine,
despite Rust paying zero header bytes — a real, honest finding that a
smaller footprint does not automatically mean faster allocation; the
JVM's bump-pointer TLAB allocation can beat repeated individual `malloc`
calls for many small objects, and this lab's own measured evidence shows
it doing exactly that here. The *qualitative* ordering held across both
languages, though: `flatPrimitiveArrays` and `reusedMutableHolder` were
fastest to read (≈0.30 ns/record each), `packedOffHeapStruct`'s read was
measurably slower than `flatPrimitiveArrays`' (≈1.45 ns/record vs.
≈0.30 ns/record) — consistent with Java's own packed-struct-slower-to-read
finding (java.md) even though Rust's absolute numbers are roughly two to
three orders of magnitude smaller — and `boxedObjectGraph`'s read
(≈1.28 ns/record) sat between them, reflecting real pointer-chasing cost
without any header cost mixed in. None of these exact numbers are
published evidence; the directions and mechanisms are what
benchmark.md's real, reproduced evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/allocation-object-layout/code/rust

# correctness gate — every variant sums to the identical total per dataset
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (four variants, ordersQuotes, construct + sumHot)
cargo bench

# THE evidence tool — run directly, any variant/dataset/op
cargo run --release --bin allocation_object_layout_evidence -- \
  --variant packedOffHeapStruct --dataset treeNodes --op sumHot
```

## Cross-language parity notes

- `boxed_object_graph`'s zero header overhead versus Java's real,
  JOL-measured header tax (java.md) is this lab's central Rust-track
  finding — not a gap Rust needs to close, and not evidence Rust is
  "faster" (its own `construct` cost is real and, on this dev run,
  larger per record than Java's).
- `packed_off_heap_struct` is included for matrix parity even though
  Rust's ordinary struct arrays already achieve the same layout more
  idiomatically — documented above, not hidden, per this lab's semantic-
  equivalence contract allowance for representations that differ by
  design rather than by accident.
- No cross-harness ranking is published between the JMH benchmarks and
  `allocation_object_layout_evidence`; the native-Linux evidence runner
  treats Java as the measured side for this lab's publication numbers
  (benchmark.md), and Rust's runs are a separately disclosed instrument.
