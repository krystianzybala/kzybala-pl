# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing.
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: ring state animation, sequence timeline, latency/CPU wait-strategy chart.
- [ ] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: using SPSC with multiple producers; publishing before payload write; coordinated omission; ignoring shutdown and wraparound.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on VarHandles, padded sequences, preallocated slots, batching and wait strategies.
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab.
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on atomics, MaybeUninit or initialized slots with documented invariants, cache padding and wait strategies.
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate.
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: primitive messages, fixed binary frames, burst and steady traffic.
- [x] 22. Execute or wire the required variants: blocking queue baseline, naive volatile ring, padded acquire/release ring, batched ring, busy-spin/yield/park wait strategies.
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [x] 24. Capture environment/toolchain metadata and comparability checks.
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: messages/s, one-way latency p50/p99/p999, CPU/core, allocation rate, failed polls.
- [ ] 27. Capture representative evidence using supported tools from: JMH group/custom harness, Criterion/custom harness, HdrHistogram, perf, jcstress, loom/miri where relevant.
- [ ] 28. Preserve raw output and import it through the canonical result schema.
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information.
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference.

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [ ] 33. Add one evidence-interpretation exercise using raw profiler/counter output.
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [ ] 35. Perform editorial review for neutrality, reproducibility and unsupported claims.

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests.
- [ ] 37. Run the smoke profile and at least one full controlled result set.
- [ ] 38. Verify raw artifact links, environment metadata and result provenance.
- [x] 39. Verify accessibility, route behavior and responsive presentation.
- [ ] 40. Run `openspec validate plab-013-spsc-ring-buffer-reference-lab --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.
