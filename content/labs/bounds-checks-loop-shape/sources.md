# Bounds checks and loop shape — sources

- Aleksey Shipilëv, *"JVM Anatomy Quark #6: Bounds Checks Elimination"* —
  the primary reference for this lab's Java-side mechanism section:
  https://shipilev.net/jvm/anatomy-quarks/6-bounds-check-elimination/
- OpenJDK HotSpot, C2 range-check elimination documentation (source
  comments in `c2_RangeCheckSmearing`/`PhaseIdealLoop` are the
  authoritative reference; no stable external doc page — see the OpenJDK
  source repository).
- The Rust Performance Book, *"Bounds Checks"* — `get_unchecked`,
  iterator-based bounds-check elision and when each applies:
  https://nnethercote.github.io/perf-book/bounds-checks.html
- Rust `std::slice` documentation — `get_unchecked`'s safety contract:
  https://doc.rust-lang.org/std/primitive.slice.html#method.get_unchecked
- LLVM documentation, *"Auto-Vectorization in LLVM"* — the relationship
  between provable loop bounds and vectorization eligibility:
  https://llvm.org/docs/Vectorizers.html
- OpenJDK, `java.lang.foreign` (Foreign Function & Memory API)
  documentation — `MemorySegment` bounds semantics:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html
- `perf-stat(1)` — branches/branch-misses counter definitions:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/cache-locality-working-set`
  (the permutation construction this lab's `irregularIndex` variant
  reuses).
