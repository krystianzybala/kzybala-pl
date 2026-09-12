# Heap vs off-heap with FFM MemorySegment — Rust track

Crate `ffm_memory_segments_lab`: the fixed constants and deterministic
generation shared with the Java side, and three per-dataset modules
(`fixed_records`, `large_numeric_buffers`, `binary_frames`), each
exposing all five storage variants behind the `RecordStorage` trait.

## Rust has no off-heap to reach for — this crate's variants are reframed honestly

A `Vec<T>` in Rust already IS native, GC-free, explicitly-owned memory —
what Java's FFM API exists to approximate for a language whose default
object model has a garbage collector and object headers. Force-mapping
Java's five variants onto Rust's single memory model would be
misleading, so this crate reframes them for what actually differs in
Rust:

- **`heap_primitive_array`** — parallel `Vec<T>`s, Rust's ordinary,
  natively-typed representation (the honest baseline, same role as
  Java's).
- **`confined_segment`** — a packed, raw-byte `Vec<u8>` buffer with
  manual offsets (`to_ne_bytes`/`from_ne_bytes`, no `unsafe` anywhere),
  owned by a single context. This is Rust's closest analog to a manually
  laid-out struct array — the "confined" framing is about single
  ownership, not a runtime-checked confinement the way Java's
  `Arena.ofConfined()` is.
- **`shared_segment`** — the IDENTICAL packed buffer, wrapped in `Arc`.
  Rust's real analog to Java's confined-vs-shared arena cost is
  `Arc`'s atomic reference-counting overhead, not a separate runtime
  confinement check — there is no such check in Rust, because ownership
  is enforced at compile time.
- **`sliced_view`** — a `&[u8]` slice at a nonzero offset into a larger
  backing buffer, mirroring Java's header-then-payload technique with
  native Rust slicing.
- **`copied_boundary_crossing`** — starts from the packed raw-byte
  buffer (as if it arrived over FFI or from a network socket) and PARSES
  it into fresh, natively-typed `Vec`s on every operation — a genuinely
  meaningful boundary crossing in Rust (raw bytes to a typed
  representation), not a contrived heap-to-heap copy.

## "Closing arena during use" is structurally impossible here

This lab's own named trap — closing an arena while another thread or a
live slice still references it — has no Rust equivalent to get wrong.
There is no manual close/lifetime step: the borrow checker rejects any
attempt to use a buffer after its owner is dropped, or to hold a slice
that outlives its backing `Vec`, at COMPILE time, before the program
ever runs. This is a real, structural difference worth stating plainly,
not a claim that Rust is "safer" in some vague sense — it is a specific,
mechanical consequence of ownership and borrowing applied to exactly the
bug class this lab's Java track has to guard against at runtime.

## What was actually measured (dev-only, never published)

Running the Criterion dev benchmark on `fixedRecords` (N = 500,000, K =
10,000 for random access) on this repository's development machine
(release build) showed the same qualitative pattern as the Java track:
`heap_primitive_array`'s `sequential_sum` (≈0.34 ns/record) was the
fastest of all five variants — every packed-buffer variant
(`confined_segment` ≈1.76, `shared_segment` ≈1.70, `sliced_view` ≈1.74
ns/record) was slower, direct confirmation that the qualitative finding
(native typed access beats manual byte-offset parsing for a
straightforward pass) holds in both languages even though the
mechanisms producing it differ (JVM `MemorySegment` access-mode checks
vs. Rust's `to_ne_bytes`/`from_ne_bytes` conversions). Running the
evidence binary on `largeNumericBuffers`' `copiedBoundaryCrossing`
`randomAccess` showed ≈176 ns/access — in the same order of magnitude as
Java's `copiedBoundaryCrossing` finding on the comparable
`fixedRecords` cell (≈141.7 ns/access), both driven by the identical
mechanism: an unconditional whole-dataset copy amortized over a small
number of useful reads. None of these exact numbers are published
evidence; the directions and mechanisms are what benchmark.md's real,
reproduced evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/ffm-memory-segments/code/rust

# correctness gate — every variant sums to the identical total per dataset
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (five variants, fixedRecords, both ops)
cargo bench

# THE evidence tool — run directly, any variant/dataset/op
cargo run --release --bin ffm_memory_segments_evidence -- \
  --variant copiedBoundaryCrossing --dataset largeNumericBuffers --op randomAccess
```

## Cross-language parity notes

- `confined_segment`/`shared_segment` are NOT claims that Rust has an
  arena-confinement mechanism — they are the closest honest analog
  (single ownership vs. `Arc`), documented as such rather than implied
  to be the same runtime check Java performs.
- This lab's "unsafe Rust without invariant documentation" trap does not
  apply to this crate's own code: every packed-buffer access uses safe
  `to_ne_bytes`/`from_ne_bytes` conversions, with zero `unsafe` blocks
  anywhere in `ffm_memory_segments_lab`.
- No cross-harness ranking is published between the JMH benchmarks and
  `ffm_memory_segments_evidence`; the native-Linux evidence runner
  treats Java as the measured side for this lab's publication numbers
  (benchmark.md), and Rust's runs are a separately disclosed instrument
  built around a structurally different memory model.
