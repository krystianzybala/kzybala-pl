# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content existed; inventoried the pre-existing Java kernel scaffolding and shared fixtures JSON before building on top of them.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures.
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only.

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: happens-before graph, ordering/fence table, jcstress/loom outcome matrix.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: using performance tests as correctness proof; mapping Java and Rust orderings mechanically; benchmarking an intentionally racy variant without isolation; omitting forbidden outcomes.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on Java Memory Model, VarHandle access modes, volatile, fences and jcstress validation. (jcstress itself is capability-unavailable in this repo — documented, not silently skipped.)
- [x] 12. Add deterministic correctness tests for every Java variant.
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default.
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (Via the shared native-Linux runner's `LAB_PROFILER_POLICY="stat"` in the new `.conf`; no lab-specific extra hooks needed.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (No repo-wide Java formatter/linter plugin exists; `mvn test` is the gate, matching other labs.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on Ordering variants, happens-before reasoning, loom model checking and unsafe-cell boundaries. (loom is capability-unavailable in this repo — documented, not silently skipped.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile.
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (Miri is capability-unavailable in this repo — documented; `unsafe` is isolated to two structs' `Sync` impls and one-line `UnsafeCell` derefs, each with a safety comment.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output.

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: single-slot mailbox, sequence flag plus payload, counter update.
- [x] 22. Execute or wire the required variants: plain broken publication demonstration, acquire/release publication, volatile/seq-cst publication, CAS loop, fence-based variant. (All five implemented and correctness-tested per dataset; the deliberately-broken variants are excluded from the throughput benchmark class itself, per the "no benchmarking a racy variant" guardrail, and instead reported via observed-not-asserted correctness-suite output.)
- [x] 23. Add smoke, full and publication run commands through repository scripts. (`scripts/performance-lab/labs/memory-ordering-atomics.conf` added; wired into `config/benchmark-hosts/precision-5810.yaml` and the `scripts/test-benchmark-batch.js` reference-tier-adjacent fixture — full batch-discovery test suite passes.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (Handled generically by the shared native-Linux runner once the `.conf` is wired; no lab-specific metadata gap identified.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Handled generically by the shared runner/import gates; verified the shared correctness-gate wrapper `scripts/benchmark-platform/run-correctness-gate.js memory-ordering-atomics` runs and reports pass for both languages.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: ops/s, failed CAS, instructions/fences, correctness outcomes, p99. (Requires the native-Linux 5810 host run.)
- [ ] 27. Capture representative evidence using supported tools from: JMH, jcstress, Criterion, loom, perfasm, cargo asm, perf stat. (Requires the native-Linux 5810 host run; jcstress/loom/miri are marked capability-unavailable regardless.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, clearly-labeled teaching example, never real captured evidence — same pattern as false-sharing/spsc-ring-buffer's Exercise 3.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims.

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test` both green, repeatedly re-verified; two real, reproduced-on-hardware ordering bugs found and fixed during construction — see `SequenceFlagKernel.fenceBased()`'s javadoc.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` passes for all 26 labs including this one; lab.json wired into `labs-index.json`/`curriculum.json` with bidirectional prerequisites against `cas-contention`.)
- [x] 40. Run `openspec validate plab-402-memory-ordering-atomics --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (Status `draft`, no canonical results claimed anywhere; benchmark.md explicitly states "Awaiting native-Linux measurement.")
