# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [ ] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing.
- [ ] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [ ] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [ ] 6. Write the theory section around the performance mechanism, not around a language winner.
- [ ] 7. Create the required visual explanations: branch predictor state diagram, branch-miss vs distribution chart, annotated assembly.
- [ ] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [ ] 9. Document the known traps: regenerating random data inside measurement; compiler removing the branch; confusing vectorization with branch prediction; using one data distribution.
- [ ] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [ ] 11. Implement the Java baseline and required variants with focus on Branchy loops, branchless transformations, C2-generated assembly and data-dependent control flow.
- [ ] 12. Add deterministic correctness tests for every Java variant.
- [ ] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [ ] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab.
- [ ] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [ ] 16. Implement the Rust baseline and required variants with focus on Branchy loops, iterator formulations, branchless masks and generated assembly.
- [ ] 17. Add deterministic correctness tests using the same fixtures as Java.
- [ ] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [ ] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate.
- [ ] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [ ] 21. Add parameter coverage for: byte flags, integer thresholds, mixed hot/cold records.
- [ ] 22. Execute or wire the required variants: 90/10 biased branch, 50/50 random branch, sorted input, branchless mask/select.
- [ ] 23. Add smoke, full and publication run commands through repository scripts.
- [ ] 24. Capture environment/toolchain metadata and comparability checks.
- [ ] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: ns/element, branches, branch-misses, IPC, cycles/element.
- [ ] 27. Capture representative evidence using supported tools from: JMH, Criterion, perf stat, perf record, perfasm, cargo asm.
- [ ] 28. Preserve raw output and import it through the canonical result schema.
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information.
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference.

## 7. Exercises and Review

- [ ] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [ ] 32. Add one implementation exercise with measurable success criteria.
- [ ] 33. Add one evidence-interpretation exercise using raw profiler/counter output.
- [ ] 34. Keep hints and solutions separate from the initial exercise statement.
- [ ] 35. Perform editorial review for neutrality, reproducibility and unsupported claims.

## 8. Verification and Publication

- [ ] 36. Run all Java and Rust correctness tests.
- [ ] 37. Run the smoke profile and at least one full controlled result set.
- [ ] 38. Verify raw artifact links, environment metadata and result provenance.
- [ ] 39. Verify accessibility, route behavior and responsive presentation.
- [ ] 40. Run `openspec validate plab-103-branch-prediction --strict` and repository quality gates.
- [ ] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.
