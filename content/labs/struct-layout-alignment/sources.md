# Struct layout, alignment and padding — sources

- Aleksey Shipilëv, *JOL (Java Object Layout)* project — the primary
  tool this lab's `JolReport` uses directly, and the source of this
  lab's central finding that the JVM does not respect field declaration
  order: https://openjdk.org/projects/code-tools/jol/
- Aleksey Shipilëv, *"Long Story of Long: How to Squeeze Every Byte"*
  and related JVM object-layout posts — HotSpot's default field-packing
  algorithm this lab's evidence confirms empirically.
- The Java `java.lang.foreign` package documentation —
  `MemoryLayout.structLayout`, `MemoryLayout.paddingLayout`,
  `ValueLayout`'s `*_UNALIGNED` variants — the API this lab's Java track
  uses directly for every sequential-access dataset:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html
- content/labs/aos-vs-soa's java.md — the offset-based packed-struct
  access technique (and its documented VarHandle regression) this lab's
  `MixedRecordLayout`/`HeaderPayloadLayout` reuse directly.
- content/labs/ffm-memory-segments — this lab's direct prerequisite;
  the confined/shared/sliced arena mechanics this lab's `MemorySegment`
  usage builds on.
- content/labs/false-sharing — this lab's direct mechanism ancestor for
  `producerConsumerCounters`; its `PaddedCounters`/`CacheLineAligned<T>`
  techniques (Java and Rust respectively) are reused here without
  modification.
- The Rust Reference, *Type Layout* chapter — `repr(Rust)` (unspecified,
  compiler-optimized), `repr(C)`, `repr(align(N))`, `repr(packed)` and
  their safety rules around packed-field references:
  https://doc.rust-lang.org/reference/type-layout.html
- The Rust Unsafe Code Guidelines, *Layout* chapter — the precise rule
  distinguishing a safe by-value read of a packed field from an unsafe
  reference to one, the mechanism this lab's zero-`unsafe` Rust track
  relies on: https://rust-lang.github.io/unsafe-code-guidelines/layout.html
- `perf-stat(1)` — cache-misses/dTLB-load-misses counter definitions
  relevant to unaligned-access and cache-line-crossing evidence:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
