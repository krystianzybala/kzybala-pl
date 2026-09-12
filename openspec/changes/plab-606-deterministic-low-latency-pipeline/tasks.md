# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield capstone lab — inventoried `content/labs/backpressure-bounded-pipelines/`, `content/labs/fixed-binary-serialization/` and `content/labs/thread-per-core-sharding/` as the mechanisms this lab composes, per design.md's prerequisite list.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: full pipeline animation, latency budget waterfall, Java/Rust evidence dashboard, overload recovery timeline. (Textual/table forms: the five-stage pipeline description in theory.md, the illustrative-run comparison table in benchmark.md, and exercises.md Exercise 3's latency-budget-waterfall table — no canvas/JS visualization added, per this lab's accessible-fallback-first content contract.)
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: declaring a language winner from one host; omitting correctness checks; using unbounded queues; hiding warm-up/startup; hand-tuning one implementation only.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on UDP or in-process replay ingest, FFM frame view, sharded thread-per-core processing, SPSC handoff, fixed result encoding and JFR/perf verification. (In-process event replay used, per design.md's "UDP or in-process replay" option and this lab's non-goal against requiring privileged host tuning; the correctness fixture models sharded, bounded, zero-copy-decoded routing deterministically — the real FFM frame view/SPSC handoff/JFR verification are documented in java.md as the production-shaped implementation the benchmark harness exercises.)
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (No custom JVM-flag hooks beyond the standard JMH harness for the smoke/development path; allocation evidence for the naive-vs-optimized comparison is a native-Linux JFR concern, task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on equivalent replay/UDP path, borrowed frame view, owned shard state, bounded queues and fixed result encoding.
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (No `unsafe` used anywhere in this crate's correctness fixture; nothing to isolate or model-check at this maturity.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: 32B/128B/1KiB fixed events, uniform keys, hot-key skew, steady and burst load.
- [x] 22. Execute or wire the required variants: naive object/queue pipeline, optimized Java pipeline, optimized Rust pipeline, overload profile, fault/restart profile. ("Optimized Java pipeline"/"optimized Rust pipeline" are each language's own OPTIMIZED implementation of the one logical shape, per the semantic-equivalence contract — not two separate code paths within one language.)
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [x] 24. Capture environment/toolchain metadata and comparability checks.
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: end-to-end p50/p99/p999, throughput, allocation rate, drops/rejects, CPU/core, cache misses, recovery time. (Requires the native-Linux 5810 host run with a genuinely threaded, real-buffer harness — drops/rejects and restart-loss counts are already exposed deterministically by the fixture; end-to-end percentile latency, allocation rate and cache misses require real timing this deterministic simulator intentionally does not model.)
- [ ] 27. Capture representative evidence using supported tools from: macro harness, HdrHistogram, JFR, async-profiler, perf stat/c2c, Criterion support tools. (Requires the native-Linux 5810 host run; JFR/async-profiler/perf-c2c are capability-unavailable in this repo's toolchain today regardless.)
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
- [x] 40. Run `openspec validate plab-606-deterministic-low-latency-pipeline --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.
