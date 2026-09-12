# Heap vs off-heap with FFM MemorySegment — sources

- JEP 454, *Foreign Function & Memory API* — the primary specification
  for `MemorySegment`, `Arena`, `ValueLayout` and confinement semantics
  this lab's Java track builds on directly:
  https://openjdk.org/jeps/454
- The `java.lang.foreign` package documentation — `Arena.ofConfined()`,
  `Arena.ofShared()`, `MemorySegment.asSlice()`:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html
- content/labs/allocation-object-layout's java.md — the offset-based
  packed-struct access technique (and its documented VarHandle
  regression) this lab's `confinedSegment`/`sharedSegment` reuse
  directly.
- content/labs/aos-vs-soa's java.md — the original, real ~100x C2
  regression from a layout-path `VarHandle` on a non-constant field, the
  reason every packed-struct variant in this repository uses precomputed
  offsets instead.
- The Rust Reference, *Ownership and Borrowing* — the compile-time
  mechanism that makes this lab's "closing arena during use" trap
  structurally unreachable in the Rust track:
  https://doc.rust-lang.org/book/ch04-00-understanding-ownership.html
- The Rust standard library, `Arc<T>` documentation — the atomic
  reference-counting mechanism this lab's Rust `shared_segment` variant
  uses as its honest analog to Java's shared-arena cost:
  https://doc.rust-lang.org/std/sync/struct.Arc.html
- `perf-stat(1)` — cache-misses/dTLB/page-fault counter definitions
  relevant to storage-location access cost:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/allocation-object-layout` (this
  lab's direct prerequisite — footprint feeds into this lab's
  heap-vs-off-heap question) and `content/labs/bounds-checks-loop-shape`
  (the bounds-check-elimination mechanism conceptually adjacent to
  `MemorySegment`'s own per-access checks).
