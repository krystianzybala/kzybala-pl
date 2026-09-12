# Array of Structures vs Structure of Arrays — sources

- Ulrich Drepper, *"What Every Programmer Should Know About Memory"*
  (2007) — cache lines, prefetching and the memory hierarchy that
  motivates this lab's density arithmetic: https://people.freebsd.org/~lstewart/articles/cpumemory.pdf
- CppCon, Mike Acton, *"Data-Oriented Design and C++"* — the canonical
  AoS-vs-SoA and hot/cold field-splitting argument from a systems/games
  background: https://www.youtube.com/watch?v=rX0ItVEVjHc
- OpenJDK, `java.lang.foreign` (Foreign Function & Memory API)
  documentation — `MemorySegment`, `MemoryLayout`, `VarHandle` access:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html
- JOL (Java Object Layout) — the tool this lab's `JolReport` uses for
  real per-instance and per-graph heap footprint:
  https://github.com/openjdk/jol
- The Rust Reference, *Type Layout* — `#[repr(C)]`, alignment and const
  generics: https://doc.rust-lang.org/reference/type-layout.html
- OpenJDK, JEP 338 / `jdk.incubator.vector` (Vector API) — the
  vectorization-eligibility discussion in theory.md references this
  API's structural requirements without depending on it directly:
  https://openjdk.org/jeps/338
- `perf-stat(1)` — cache-references/cache-misses/LLC counter definitions:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Brendan Gregg, *Systems Performance* (2nd ed.), ch. 6–7 — memory and
  cache-hierarchy measurement methodology.
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
