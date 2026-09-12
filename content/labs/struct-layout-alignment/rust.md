# Struct layout, alignment and padding — Rust track

Crate `struct_layout_alignment_lab`: `mixed_record` and `header_payload`
modules (five `repr`-controlled struct variants each), and
`producer_consumer_counters` (five atomic-counter layout variants,
mirroring content/labs/false-sharing's mechanism).

## `repr` is where Rust makes layout control explicit — and where it doesn't

- **Default `repr(Rust)` (no attribute) is unspecified — like the JVM,
  the compiler may reorder fields.** `mixed_record::Natural` is declared
  with NO `repr` attribute, in the same field order as the Java track's
  hand-computed 32-byte "natural" layout. Its REAL, measured
  `size_of::<Natural>()` is **24 bytes** — the theoretical minimum,
  identical to the hand-optimized `#[repr(C)]` variant — because Rust's
  default layout algorithm already reorders for tight packing
  automatically. This is a genuine, real difference from Java's FFM
  track, where "natural" (no reordering) landed at 32 bytes because FFM
  never reorders at all, by design (java.md). Reordering fields in a
  default-`repr` Rust struct is, like a plain Java object, a no-op for
  footprint purposes.
- **`#[repr(C)]` makes declaration order a hard guarantee, following the
  same natural-alignment algorithm as C and this lab's Java FFM
  layouts.** `PoorFieldOrder` and `OptimizedFieldOrder` use `#[repr(C)]`
  specifically so their field order is respected exactly — this crate's
  own tests assert `size_of::<PoorFieldOrder>() == 40` and
  `size_of::<OptimizedFieldOrder>() == 24`, matching the Java track's
  hand-computed byte layouts exactly, offset for offset. This is the
  cross-language equivalence contract for this lab: bytes, not just
  checksums.
- **`#[repr(C, align(64))]` is Rust's first-class cache-line-alignment
  tool — no manual trailing-padding arithmetic needed.** The Java track
  has to compute exact trailing padding bytes and pass an explicit
  segment alignment to `Arena.allocate`; Rust's `align(N)` attribute
  makes the compiler guarantee every instance (and every array element)
  starts on an N-byte boundary automatically. `mixed_record::CacheLineAligned`
  needed zero extra arithmetic beyond the attribute itself.
- **`#[repr(C, packed)]` forbids REFERENCES to misaligned fields, not
  READS of their values.** `mixed_record::PackedUnaligned` and
  `header_payload::PackedUnaligned` place `amount_ticks`/`sequence` (and,
  for the header dataset, the whole `payload` array) at unaligned byte
  offsets — matching the Java track's unaligned offsets exactly. Every
  `sum_*` function in this crate reads each field OUT BY VALUE
  (`let v = r.field;`) rather than taking a reference (`&r.field`),
  which is what makes this crate compile with **zero `unsafe` blocks
  anywhere** — the compiler emits the correct unaligned load for a
  by-value `Copy` read automatically. For the array field specifically,
  the whole `[u64; 8]` array is copied out by value FIRST
  (`let payload: [u64; 8] = r.payload;`), then indexed from the now-local,
  ordinarily-aligned copy — the concrete technique this lab's "unsafe
  references to packed fields" trap points at avoiding.

## What was actually measured (dev-only, never published)

Running the Criterion dev benchmark and the evidence binary on
`mixedPrimitiveRecord` on this repository's development machine (release
build) showed `optimizedFieldOrder`'s sequential sum at ≈0.82 ns/record
— in the same qualitative range as the Java track's own optimized-layout
number, despite the two languages' packed-access mechanisms
(`MemorySegment` checks vs. direct `#[repr(C)]` field reads) being
structurally different. Running the evidence binary's two-thread
`producerConsumerCounters` throughput showed `cacheLineAligned` at
≈55.3M combined increments/sec versus `natural` at ≈23.7M — a real
≈2.3× throughput gap in the SAME direction as the Java track's ≈4.6–5.3×
gap (java.md), though smaller in magnitude on this run; both results are
consistent with cache-line separation being the only technique in this
lab's matrix that actually changes coherence behavior, in either
language. None of these exact numbers are published evidence; the
directions and mechanisms are what benchmark.md's real, reproduced
evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/struct-layout-alignment/code/rust

# correctness gate — every variant sums to the identical total per dataset;
# repr(C) layouts are asserted to match the Java track's byte layouts exactly;
# every counter variant counts exactly right under real concurrency
cargo test

# lints and formatting
cargo clippy --all-targets
cargo fmt --check

# dev/wiring Criterion bench (five variants, mixedPrimitiveRecord)
cargo bench

# THE evidence tool — sequential datasets
cargo run --release --bin struct_layout_alignment_evidence -- \
  --variant cacheLineAligned --dataset headerPlusPayload

# THE evidence tool — producerConsumerCounters (two-thread throughput)
cargo run --release --bin struct_layout_alignment_evidence -- \
  --variant cacheLineAligned --dataset producerConsumerCounters
```

## Cross-language parity notes

- This lab's "unsafe references to packed fields" trap is directly,
  concretely avoided in this crate: zero `unsafe` blocks anywhere,
  because every packed-struct access is a by-value `Copy` read, never a
  reference. This is a genuine, real technique, not a simplification —
  the same technique would work in the Java track's spirit too, since
  `MemorySegment.get` also returns values, never references.
  content/labs/aos-vs-soa's `AosPackedLayout` java.md and this crate
  independently arrive at "compute offsets once, read values, never take
  unaligned references" as the safe pattern in both languages.
- `mixed_record::Natural`'s measured 24-byte size (vs. Java's
  hand-computed, never-reordered 32-byte "natural" layout) is this lab's
  central Rust-track finding — not evidence Rust is "better," but a real,
  structural difference in what each language's DEFAULT (unattributed)
  layout guarantees, disclosed here rather than smoothed over.
- No cross-harness ranking is published between the JMH benchmarks and
  `struct_layout_alignment_evidence`; the native-Linux evidence runner
  treats Java as the measured side for this lab's sequential-access
  publication numbers (benchmark.md), and Rust's runs (both sequential
  and counter throughput) are a separately disclosed instrument.
