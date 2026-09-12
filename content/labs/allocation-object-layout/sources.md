# Allocation cost and object layout — sources

- Aleksey Shipilëv, *JOL (Java Object Layout)* project and its associated
  blog posts on JVM object header/alignment mechanics — the primary
  reference and the tool this lab's `JolReport` uses directly to capture
  real, not estimated, per-instance and per-graph footprint:
  https://openjdk.org/projects/code-tools/jol/
- Aleksey Shipilëv, *"Compressed OOPs"* and related JVM internals posts —
  the compressed-reference and object-alignment mechanics this lab's
  theory page describes.
- Charlie Hunt & Binu John, *Java Performance* — the object layout and
  memory footprint chapters, shared lineage with content/labs/aos-vs-soa.
- The Java Language and JVM specifications, `java.lang.foreign` (FFM
  API) — `MemorySegment`, `MemoryLayout`, `ValueLayout` — the API this
  lab's `PackedOffHeapStruct` uses directly:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html
- The Rust Reference, *Type Layout* chapter — struct field ordering,
  alignment and `repr` behavior, the mechanism this lab's Rust track
  relies on for its zero-header-overhead finding:
  https://doc.rust-lang.org/reference/type-layout.html
- The Rust standard library, `Box<T>` and `to_ne_bytes`/`from_ne_bytes`
  documentation — the heap-allocation and safe byte-conversion
  primitives this lab's Rust variants use directly:
  https://doc.rust-lang.org/std/boxed/struct.Box.html,
  https://doc.rust-lang.org/std/primitive.u64.html#method.to_ne_bytes
- `perf-stat(1)` — cache-misses/LLC/dTLB counter definitions relevant to
  footprint-driven locality effects:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/aos-vs-soa` (the packed-struct
  offset-based access technique and its documented VarHandle pitfall this
  lab's `PackedOffHeapStruct` reuses directly) and
  `content/labs/cache-locality-working-set` (this lab's direct
  prerequisite — footprint feeds locality).
