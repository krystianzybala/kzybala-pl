# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no pre-existing route/content to migrate; inventoried sibling labs `content/labs/thread-per-core-sharding/` and `content/labs/locks-spin-parking/` as structural templates before writing anything.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: task scheduling diagram, latency vs concurrency, thread/runtime state view. (Textual/table forms in theory.md: the five-phase task lifecycle table, the illustrative-run comparison table in benchmark.md serving as the latency-vs-concurrency view, and the terminology table's carrier/pinning state model — no canvas/JS visualization added, per this lab's accessible-fallback-first content contract.)
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: using sleep as the only I/O model; claiming virtual threads are low-latency threads; adding Tokio only to win a chart; mixing CPU capacity across models.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on virtual/platform threads, executors, pinning diagnostics, structured concurrency where available and event-loop baseline.
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (No allocation/JVM-flag hooks beyond the standard JMH harness are needed — this lab's mechanism is scheduling/carrier occupancy, not allocation; documented in theory.md Assumptions and scope.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on async runtime already approved by repo if any, OS threads and single-thread event loop; avoid adding a runtime solely for the lab without justification. (No async runtime precedent exists in this repo and none was added — every variant uses only `std::thread` and a manual deadline-heap event loop, per design.md's explicit guidance.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (No `unsafe` used anywhere in this crate — scoped threads, channels and atomics are sufficient; nothing to isolate or model-check.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: simulated socket wait, short CPU stage, long CPU stage, lock/native pinning case.
- [x] 22. Execute or wire the required variants: thread-per-request platform, virtual thread per task, fixed event loop, CPU-bound pool, mixed blocking and CPU workload.
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [x] 24. Capture environment/toolchain metadata and comparability checks.
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: throughput, p50/p99/p999, scheduler CPU, thread count, context switches, pinning events. (Requires the native-Linux 5810 host run.)
- [ ] 27. Capture representative evidence using supported tools from: JFR, async-profiler wall/cpu, JMH or macro harness, Criterion/custom Rust harness, perf sched. (Requires the native-Linux 5810 host run; async-profiler and JFR-based pinning-event capture are capability-unavailable in this repo's toolchain today regardless — documented in exercises.md Exercise 3 and theory.md Limitations.)
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
- [x] 40. Run `openspec validate plab-601-virtual-platform-event-loop --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.
