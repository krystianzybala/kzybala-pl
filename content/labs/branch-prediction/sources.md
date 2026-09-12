# Branch prediction and data distribution — sources

- The canonical "why is processing a sorted array faster" experiment and
  discussion (branch prediction, StackOverflow, 2012):
  https://stackoverflow.com/questions/11227809/why-is-processing-a-sorted-array-faster-than-processing-an-unsorted-array
- Agner Fog, *"The microarchitecture of Intel, AMD and VIA CPUs"* —
  branch prediction, misprediction penalties and pipeline depth by
  microarchitecture: https://www.agner.org/optimize/microarchitecture.pdf
- Dan Luu, *"Branch prediction"* — accessible overview of saturating
  counters, history-based predictors and BTBs:
  https://danluu.com/branch-prediction/
- James E. Smith, *"A Study of Branch Prediction Strategies"*, ISCA 1981 —
  the original 2-bit saturating-counter predictor this lab's theory page
  models.
- Intel, *Intel 64 and IA-32 Architectures Optimization Reference Manual*,
  chapter on branch prediction — microarchitecture-specific misprediction
  penalties and prediction hints: https://www.intel.com/content/www/us/en/developer/articles/technical/intel-sdm.html
- Aleksey Shipilëv, *JMH samples* — `JMHSample_17_BranchPrediction`, the
  reference JMH pattern this lab's benchmark structure follows:
  https://github.com/openjdk/jmh/blob/master/jmh-samples/src/main/java/org/openjdk/jmh/samples/JMHSample_17_BranchPrediction.java
- Rust `std::hint::black_box` documentation (preventing dead-code
  elimination in the Criterion benches):
  https://doc.rust-lang.org/std/hint/fn.black_box.html
- `perf-stat(1)` — branches/branch-misses/IPC counter definitions:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Brendan Gregg, *Systems Performance* (2nd ed.), ch. 6 — CPU
  architecture, IPC and hardware counters.
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
