# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing.
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: benchmark lifecycle timeline, warm-up convergence chart, misleading vs corrected result comparison.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: benchmarking debug builds; sharing mutable state between iterations; comparing default JMH and Criterion settings as if identical; publishing only the best run.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on JMH state scopes, forks, warm-up, Blackhole, @CompilerControl, parameterization and profilers.
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab.
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on Criterion configuration, black_box, setup placement, sample size, measurement time and cargo bench profiles.
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate.
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: tiny scalar operation, array reduction, parser with reusable input, stateful counter.
- [x] 22. Execute or wire the required variants: constant-foldable input vs runtime input, returned result vs consumed result, setup inside timed region vs outside, single fork/process vs isolated forks/processes.
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [ ] 24. Capture environment/toolchain metadata and comparability checks.
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: ns/op, ops/s, confidence interval, variance, allocation/op.
- [ ] 27. Capture representative evidence using supported tools from: JMH, Criterion, perf stat, perfasm or JIT assembly, cargo asm or objdump.
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
- [x] 40. Run `openspec validate plab-101-benchmark-harness-traps --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.

## Status note (2026-07-19)

Tasks 24, 26–30, 37 (host portion), 38 and 39 require executed
native-Linux evidence: the runner configuration is batch-ready, all
correctness suites and repository quality gates pass (task 40 ran green
twice), and dev smoke validated the wiring — but no canonical measurement
has been collected or imported for this lab yet. Metric capture, evidence
import, uncertainty tables, provenance verification and the
imported-data route review remain open until the dedicated host produces
and review accepts the evidence. Evidence maturity:
awaiting-native-linux-measurement (task 41 honored — nothing promoted
beyond what was actually achieved).
