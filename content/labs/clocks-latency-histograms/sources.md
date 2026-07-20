# Clocks, latency histograms and percentiles — sources

- Gil Tene, *"How NOT to Measure Latency"* — the canonical coordinated-
  omission talk: https://www.infoq.com/presentations/latency-response-time/
- HdrHistogram (Java) — design, precision model and corrected recording:
  https://github.com/HdrHistogram/HdrHistogram
- `hdrhistogram` crate (Rust port used by this lab):
  https://docs.rs/hdrhistogram/
- `System.nanoTime()` specification (monotonicity and what it does not
  promise): https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/System.html#nanoTime()
- `std::time::Instant` documentation (monotonicity guarantees):
  https://doc.rust-lang.org/std/time/struct.Instant.html
- Aleksey Shipilëv, *"Nanotrusting the Nanotime"* — clock granularity,
  latency and omission at the nanobenchmark scale:
  https://shipilev.net/blog/2014/nanotrusting-nanotime/
- Linux `clock_gettime`/vDSO and clocksource documentation:
  https://man7.org/linux/man-pages/man2/clock_gettime.2.html
- Brendan Gregg, *Systems Performance* (2nd ed.), ch. 2 — latency
  distributions, percentiles and workload characterization.
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
