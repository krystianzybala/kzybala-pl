# Inlining and call-site shape — sources

- Cliff Click, *"The Java HotSpot Server Compiler"* and follow-up talks —
  the original description of C2's polymorphic inline caches and
  speculative inlining, the primary reference for this lab's mechanism
  section.
- Aleksey Shipilëv, *"JVM Anatomy Quark #23: Inlining and Intrinsics"* and
  related JVM Anatomy Quarks on de/inlining and megamorphic dispatch:
  https://shipilev.net/jvm/anatomy-quarks/
- JITWatch (AdoptOpenJDK) — the inlining/JIT-log visualization tool this
  lab's evidence panel references: https://github.com/AdoptOpenJDK/jitwatch
- OpenJDK HotSpot, `-XX:+PrintInlining` / `-XX:+PrintCompilation`
  diagnostic flags — see `java -XX:+PrintFlagsFinal -version | grep -i inline`
  on a debug or diagnostic-unlocked build for the exact thresholds in use.
- The Rust Reference, *"Trait objects"* and *"Monomorphization"* — the
  compile-time-vs-runtime dispatch distinction this lab's rust.md leans
  on: https://doc.rust-lang.org/reference/types/trait-object.html
- Rust `#[inline]` attribute documentation — the deterministic hint this
  lab's `oversizedCallee` variant uses on the Rust side:
  https://doc.rust-lang.org/reference/attributes/codegen.html#the-inline-attribute
- LLVM documentation, *"Inline Cost Analysis"* — the general cost-model
  heuristics behind LLVM's own inlining decisions:
  https://llvm.org/docs/InlineAdvisor.html
- `perf-stat(1)` — branches/branch-misses/instructions counter
  definitions: https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/jit-pipeline` (tiered
  compilation and deoptimization, the prerequisite this lab builds on).
