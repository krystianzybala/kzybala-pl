# Benchmark harness traps — sources

- Aleksey Shipilëv, *JMH samples* — the canonical walkthrough of DCE,
  constant folding, Blackhole and fork semantics:
  https://github.com/openjdk/jmh/tree/master/jmh-samples/src/main/java/org/openjdk/jmh/samples
- Aleksey Shipilëv, *"Nanotrusting the Nanotime"* — why raw nanosecond
  timings mislead and how harnesses earn trust:
  https://shipilev.net/blog/2014/nanotrusting-nanotime/
- OpenJDK JMH project page: https://openjdk.org/projects/code-tools/jmh/
- Criterion.rs user guide — configuration, `black_box`, `iter_batched`
  and analysis model: https://bheisler.github.io/criterion.rs/book/
- `std::hint::black_box` documentation (Rust):
  https://doc.rust-lang.org/std/hint/fn.black_box.html
- Georges, Buytaert, Eeckhout, *"Statistically Rigorous Java Performance
  Evaluation"* (OOPSLA 2007) — the case for multi-JVM (fork) sampling and
  confidence intervals: https://dri.es/files/oopsla07-georges.pdf
- HotSpot tiered compilation overview (OpenJDK docs):
  https://docs.oracle.com/en/java/javase/22/vm/java-virtual-machine-technology-overview.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
