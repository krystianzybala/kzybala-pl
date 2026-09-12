# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — inventoried `content/labs/backpressure-bounded-pipelines/` for the bounded-reject policy pattern reused here.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: instrumentation pipeline, cost stack, latency vs sampling chart. (Textual/table forms: the three-cost breakdown in theory.md, the illustrative-run comparison table in benchmark.md, and exercises.md Exercise 3's flame-graph-style breakdown table — no canvas/JS visualization added, per this lab's accessible-fallback-first content contract.)
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: benchmarking logger initialization; unbounded async queue; hiding message loss; high-cardinality metrics without memory accounting.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on logging guards, parameterized logging, counters, JFR events, async-profiler modes and allocation-aware context propagation. (JFR events/async-profiler modes are evidence-collection concerns for the native-Linux run — see task 27; the runnable kernel itself implements the guard/counter/queue mechanisms directly.)
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (No custom JVM-flag hooks beyond the standard JMH harness are needed for the smoke/development path; allocation evidence is a native-Linux JFR concern, task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on tracing/log macros, disabled-level behavior, atomics, sampling and profiler overhead. (No `tracing`/`log` crate dependency added — plain `format!` and `std::backtrace::Backtrace::force_capture()` are sufficient and avoid an unneeded framework dependency, per design.md's non-goal.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (No `unsafe` used anywhere in this crate; nothing to isolate or model-check.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: single event, error burst, high-cardinality key, stack trace path.
- [x] 22. Execute or wire the required variants: no instrumentation, disabled eager logging, disabled lazy logging, synchronous logging, async bounded logging, metrics labels, sampled tracing.
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [x] 24. Capture environment/toolchain metadata and comparability checks.
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: ns/event, B/event, p99/p999, lost log count, CPU, contention. (Requires the native-Linux 5810 host run for allocation/contention profiling; lost log count is already exposed as `droppedCount`/`dropped_count` in the deterministic fixture and benchmark harness.)
- [ ] 27. Capture representative evidence using supported tools from: JMH, Criterion, JFR, async-profiler, perf, allocation profiler. (Requires the native-Linux 5810 host run; JFR/async-profiler/allocation-profiler evidence is capability-unavailable in this repo's toolchain today regardless.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output.
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims.

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests.
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation.
- [x] 40. Run `openspec validate plab-604-observability-overhead --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.
