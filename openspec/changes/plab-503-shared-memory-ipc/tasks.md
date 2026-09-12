# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content exists at `content/labs/shared-memory-ipc/`; nothing to migrate.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/shared-memory-ipc-fixtures.json`; one shared versioned ring header/slot layout, deterministic message/checksum derivation, asserted identically by both languages' test suites.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisite `memory-mapped-files` wired bidirectionally in `assets/data/labs-index.json` and that lab's own `unlocks`; `assets/data/curriculum.json` already had a pre-populated `planned` entry, left untouched.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: process/segment ownership diagram, sequence protocol animation, latency by payload size.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: using same-process threads and calling it IPC; missing cross-process memory-order proof; leaking stale shared segments; ignoring version mismatch and crash state.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on FFM/mapped segment, explicit header/layout, VarHandles, sequence protocol and process launcher. (`SharedRing` (FFM `MemorySegment` + VarHandle release/acquire cursors), `SocketBaselineKernel`, `ProducerMain`/`ConsumerMain` real standalone process entry points.)
- [x] 12. Add deterministic correctness tests for every Java variant. (`SharedRingOperationsTest` (in-process, two independent mappings — protocol-logic proof) and `ProcessLauncherTest` (real spawned OS processes — the actual cross-process proof, including a restart/recovery scenario).)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`ShmIpcBenchmark`; `ProcessLauncherTest`/`ProducerMain`/`ConsumerMain` serve as the "dedicated harness for cross-process experiments where JMH is not the right abstraction" design.md calls for.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (HdrHistogram-style percentile evidence and perf are the designated tools per design.md; exercised in Exercise 3 as a synthetic teaching example — real capture deferred to the native-Linux run, see task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (`mvn test` green, including the real multi-process tests.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on mmap-backed region, atomics, repr(C) header and safe wrapper around shared memory. (`SharedRing` in `src/lib.rs` (`memmap2::MmapMut` + `AtomicU64`/`AtomicI32` constructed from raw pointers into the mapping, isolated and documented), `producer`/`consumer` real standalone binaries.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java. (Same message/checksum derivation, same header layout, same restart/recovery scenario semantics.)
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`benches/shared_memory_ipc.rs`, `[profile.bench] inherits = "release"`.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (All `unsafe` confined to two small helper functions (`atomic_u64_at`/`atomic_i32_at`) plus the two `memmap2::map_mut` calls, each with a `# Safety` doc section; miri is not run against this crate because `memmap2`'s raw-pointer-to-shared-file access pattern is a known miri-incompatible case documented by the crate itself — capability-detected as not applicable rather than silently skipped.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test --release` (including `tests/process_launcher.rs`, real spawned processes), `cargo clippy --release --all-targets -- -D warnings`, `cargo fmt --check` all clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: fixed 32-byte/128-byte/1KiB messages, steady and burst load. (Three payload-size profiles implemented as JMH `@Param`/Criterion profile sweep; steady load is the harness's default pacing — see benchmark.md's operation definitions. Burst load is not separately implemented as a distinct benchmark variant in this pass; documented as a scope limitation in theory.md's Assumptions and scope rather than fabricated.)
- [x] 22. Execute or wire the required variants: pipe/socket baseline, shared memory copy payload, shared memory slot view, batched messages, process restart/recovery scenario. (All five implemented — the first four as benchmarked variants, restart/recovery as a dedicated correctness/resilience integration test per its own nature, documented in benchmark.md.)
- [x] 23. Add smoke, full and publication run commands through repository scripts. (Documented in `benchmark.md`; `.conf` wired for the unified runner.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (`.conf` declares `LAB_CPU_SET_REQUIREMENT=core_single`/exact cardinality, `LAB_LANGUAGES`, `EV_SELECTOR`, `LAB_PROFILER_POLICY`; wired into `config/benchmark-hosts/precision-5810.yaml` and `scripts/test-benchmark-batch.js` fixtures.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Inherited from the shared unified runner/correctness-gate mechanism — no per-lab override needed.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: one-way p50/p99/p999, round-trip latency, messages/s, CPU/process, fault/recovery time. (Requires the native-Linux 5810 host run.)
- [ ] 27. Capture representative evidence using supported tools from: custom process harness, HdrHistogram, perf, JFR, Criterion support utilities. (Requires the native-Linux 5810 host run; the custom process harness itself — `ProcessLauncherTest`/`process_launcher.rs` — already exists and passes locally, task remains open only for the imported-evidence portion.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, explicitly-labeled HdrHistogram percentile-table example — never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Reviewed theory.md/java.md/rust.md/benchmark.md/exercises.md for Java-vs-Rust winner claims, unsupported claims, and reproducibility — none found; benchmark.md explicitly discloses the small copy-vs-view gap in this run rather than overstating the zero-copy advantage.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test --release` both green, including real multi-process integration tests in both languages.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` and `npm run validate:curriculum` pass; no new a11y/consistency issues from this lab's own content.)
- [x] 40. Run `openspec validate plab-503-shared-memory-ipc --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (`lab.json` status remains `draft`; benchmark.md's canonical section states "Awaiting native-Linux measurement".)
