# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing.
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: byte layout diagram, logical vs physical size, allocation flame graph.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: ignoring alignment; using packed unaligned fields unsafely; comparing object graph with reduced functionality; measuring only construction.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on JOL, compressed references, records/classes, arrays, TLAB behavior and allocation profiling.
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab.
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output.

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on size_of, repr variants, Box/Vec, enum niche optimization and allocator behavior.
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate.
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: orders/quotes, tree nodes, small tuples.
- [x] 22. Execute or wire the required variants: boxed object graph, flat primitive arrays, packed off-heap struct, reused mutable holder.
- [x] 23. Add smoke, full and publication run commands through repository scripts.
- [x] 24. Capture environment/toolchain metadata and comparability checks. (Conf declares LAB_CPU_SET_REQUIREMENT/exact cardinality, LAB_LANGUAGES, EV_SELECTOR, LAB_PROFILER_POLICY; verified via a real `run-linux-evidence.sh --preflight-only --dry-run` pass — including the correctness gate and environment-metadata capture steps — on this host, and via `run-all-benchmarks.sh --dry-run` against the real precision-5810.yaml showing this lab READY, not BLOCKED.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results.

## 6. Evidence and Results

- [ ] 26. Capture these metrics: bytes/logical-record, B/op, allocation rate, cache misses, construction ns/record.
- [ ] 27. Capture representative evidence using supported tools from: JOL, JMH, JFR, async-profiler alloc, Criterion, size_of, heap profiler.
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
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`npm run check:a11y` and `npm run check:consistency` show zero problems attributable to this lab's own content; check:consistency is fully OK, and the only outstanding check:a11y findings are pre-existing, unrelated target/ build artifacts in a different lab, out of scope here.)
- [x] 40. Run `openspec validate plab-301-allocation-object-layout --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion.
