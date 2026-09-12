# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content existed; confirmed via `content/labs/thread-per-core-sharding/` absence before creating any file.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/thread-per-core-sharding-fixtures.json`, mirrored byte-for-byte by `TpcFixtures.java`/`WorkloadPlan.java` and Rust's `fixtures`/`WorkloadPlan`; equivalence contract documented in java.md/rust.md.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisites/unlocks wired bidirectionally against `locks-spin-parking` AND `thread-per-core`, matching `assets/data/curriculum.json`'s authoritative prerequisite pair — not the proposal's `PLAB-403`/`PLAB-404`/`PLAB-603` references, which curriculum.json supersedes for this lab the same way it did for the rest of this curriculum chain.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: sharding topology, skew impact on a sharded design, rebalance cost timeline.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: hiding routing cost; using random keys that eliminate realistic skew; pinning without topology disclosure; calling shared-nothing globally lock-free.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants: shared concurrent map, fixed shards with mutex, single-writer thread-per-core, skewed key distribution, rebalance simulation. (`SharedMapKernel` — flat `AtomicLongArray`, no partitioning; `MutexShardsKernel` — per-shard `ReentrantLock`; `SingleWriterKernel` — per-shard `ConcurrentLinkedQueue` inbox + single owner thread; skew is a `WorkloadPlan` property consumed by all three mechanisms; `RebalanceSimulationKernel` — two-phase stop-the-world remap.)
- [x] 12. Add deterministic correctness tests for every Java variant. (`TpcOperationsTest`, all 3 mechanisms × 2 datasets, plus the rebalance simulation.)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`TpcBenchmark`.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (Via the shared native-Linux runner's `LAB_PROFILER_POLICY="stat"` in the new `.conf`.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (No repo-wide Java formatter/linter plugin exists; `mvn test` is the gate, matching other labs; green across 8 repeated runs.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants: shared concurrent map, fixed shards with mutex, single-writer thread-per-core, skewed key distribution, rebalance simulation. (`shared_map`, `mutex_shards`, `single_writer`, `rebalance_simulation` modules mirroring the Java kernels method-for-method; `single_writer` uses `std::sync::mpsc` per shard, matching this lab's prerequisite MPSC lab's "library queue" reference point.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java.
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`[profile.bench] inherits = "release"` in Cargo.toml.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (No `unsafe` code in this crate at all — every variant is built from `std::sync`/`std::sync::atomic`/`std::sync::mpsc` safe primitives, documented in rust.md's "unsafe inventory.")
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test` green across 8 repeated runs, `cargo clippy --all-targets -- -D warnings` clean after fixing two `useless_vec` findings, `cargo fmt --check` clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: account/order state updates, per-key counters, partitioned aggregation. (Per-key counters dataset wired, as both uniform and skewed. **Disclosed scope gap**: "account/order state updates" is modeled as a simplified stand-in via the per-key counter workload; "partitioned aggregation" as its own distinct dataset is not yet parameterized — documented honestly in theory.md's Assumptions and scope rather than fabricated, the same pattern as this lab's prerequisites' own disclosed gaps.)
- [x] 22. Execute or wire the required variants: shared concurrent map, fixed shards with mutex, single-writer thread-per-core, skewed key distribution, rebalance simulation. (All five implemented, correctness-tested, and benchmarked in both languages.)
- [x] 23. Add smoke, full and publication run commands through repository scripts. (`scripts/performance-lab/labs/thread-per-core-sharding.conf` added; wired into `config/benchmark-hosts/precision-5810.yaml` and the `scripts/test-benchmark-batch.js` fixture — full batch-discovery test suite passes, 44/44.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (Handled generically by the shared native-Linux runner once the `.conf` is wired; verified via a real `run-linux-evidence.sh --preflight-only --dry-run` pass on this host, and via `run-all-benchmarks.sh --dry-run` against the real precision-5810.yaml showing this lab READY, not BLOCKED.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Handled generically by the shared runner/import gates; verified the shared correctness-gate wrapper `scripts/benchmark-platform/run-correctness-gate.js thread-per-core-sharding` runs and reports pass for both languages.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: end-to-end p50/p99/p999, throughput, cross-core transfers, skew, queue depth, rebalance cost. (Requires the native-Linux 5810 host run; `rebalanceCostNanos`/`rebalance_cost_nanos` is captured by the kernel itself but not yet imported as canonical evidence.)
- [ ] 27. Capture representative evidence using supported tools from: custom load harness, JFR, async-profiler, perf c2c, Criterion/custom Rust harness. (Requires the native-Linux 5810 host run; JFR/async-profiler/perf c2c are capability-unavailable in this repo's toolchain today regardless.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure — including the single-writer routing-cost finding — are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, clearly-labeled teaching example, never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Confirmed no Java-vs-Rust winner claims; the single-writer-costs-more-than-mutex-shards finding disclosed honestly in both languages rather than hidden or explained away; the partitioned-aggregation/account-order-state scope gap disclosed rather than hidden.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test` both green, repeatedly re-verified across 8 runs each.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` passes for all 29 labs including this one; `npm run check:a11y`/`check:consistency` show zero new problems from this lab; check:consistency fully OK; the only check:a11y findings are pre-existing, unrelated target/ build artifacts in a different lab.)
- [x] 40. Run `openspec validate plab-406-thread-per-core-sharding --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (Status `draft`, no canonical results claimed anywhere; benchmark.md explicitly states "Awaiting native-Linux measurement"; the partitioned-aggregation/account-order-state scope gap and the stop-the-world rebalance simplification are both disclosed, not hidden.)
