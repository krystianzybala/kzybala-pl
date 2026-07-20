# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing.
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: histogram and percentile curve, coordinated-omission animation, clock-overhead calibration plot.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: averages hiding tails; mixing service time and response time; negative or non-monotonic assumptions; recording allocations in the measured path.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on System.nanoTime calibration, JMH timers, HdrHistogram integration and allocation-free recording.
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab.
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on Instant timing, std::time::Instant calibration, histogram crates and per-thread recording.
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate.
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: fixed-cost operation, bimodal synthetic latency, periodic pause injection, burst workload.
- [x] 22. Execute or wire the required variants: timestamp every operation, sampled timestamps, coordinated omission corrected recording, per-thread histogram merge.
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [ ] 24. Capture environment/toolchain metadata and comparability checks.
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: timer overhead, p50, p95, p99, p99.9, max, histogram footprint.
- [ ] 27. Capture representative evidence using supported tools from: JMH, Criterion, HdrHistogram-compatible tooling, perf stat.
- [ ] 28. Preserve raw output and import it through the canonical result schema.
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information.
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference.

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output.
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims.

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests.
- [ ] 37. Run the smoke profile and at least one full controlled result set.
- [ ] 38. Verify raw artifact links, environment metadata and result provenance.
- [ ] 39. Verify accessibility, route behavior and responsive presentation.
- [x] 40. Run `openspec validate plab-102-clocks-latency-histograms --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.

## Status note (2026-07-19)

Tasks 24, 26–30, 37 (host portion), 38 and 39 require executed
native-Linux evidence: the runner configuration is batch-ready (8
variants: 4 JMH recording-cost shapes + calibration, two deterministic
replays and the two-worker merge harness), all correctness suites and
repository quality gates pass, and dev smoke validated wiring — but no
canonical measurement has been collected or imported yet. Distribution
mechanics are deterministic shared fixtures (verified in both languages,
including one documented instrument difference: the naive-p999
stall-cliff tie, pinned per implementation and excluded from
cross-language comparison). Evidence maturity:
awaiting-native-linux-measurement — nothing promoted beyond what was
actually achieved.
