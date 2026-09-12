# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content exists at `content/labs/memory-mapped-files/`; nothing to migrate.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/memory-mapped-files-fixtures.json`; one shared fixed 64-byte record format, deterministic id/value/payload derivation, asserted identically by both languages' test suites.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisite `fixed-binary-serialization` wired bidirectionally in `assets/data/labs-index.json` and that lab's own `unlocks`; `assets/data/curriculum.json` already had a pre-populated `planned` entry, left untouched.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: virtual-to-page mapping, fault timeline, warm/cold comparison.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: dropping caches without root/disclosure; confusing page cache with process memory; excluding first touch; depending on forced unmapping hacks.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on FileChannel.map or FFM mapping where available, page touching, slicing and cleanup semantics. (`BufferedReadKernel`, `MmapReadKernel` (sequential + random), `MmapWriteFlushKernel`, using `FileChannel.map`/`MappedByteBuffer`.)
- [x] 12. Add deterministic correctness tests for every Java variant. (`MmapOperationsTest`: checksum equality across buffered/sequential-mmap/random-mmap/write+flush, permutation determinism.)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`MmapBenchmark`, dataset/warm-mapping setup in `@Setup(Level.Trial)`.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (perf stat page-fault counters are the designated tool per design.md; exercised in Exercise 3 as a synthetic teaching example — real capture deferred to the native-Linux run, see task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (`mvn test` green.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on memmap or direct mmap wrapper with safe slice boundaries and flush behavior. (`memmap2`-based `map_readonly`/`map_readwrite` in `src/lib.rs`, unsafe isolated to exactly those two call sites.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java. (Same small fixture, same checksum oracle, same permutation algorithm verified index-for-index deterministic.)
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`benches/memory_mapped_files.rs`, `[profile.bench] inherits = "release"`.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (Two `unsafe` blocks total — `Mmap::map`/`MmapMut::map_mut` — each with a `# Safety` doc section explaining the concurrent-mutation UB risk and why this lab's usage avoids it; everything else is safe slice indexing.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test --release`, `cargo clippy --release -- -D warnings`, `cargo fmt --check` all clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: 64 MiB file, larger-than-RAM optional profile, fixed records. (`fixedRecords` (256 KiB, correctness/smoke) and `sixtyFourMebibyteFile` (1,048,576 records, primary benchmark dataset) implemented; `largerThanRam` capability-detected as not exercised on this repository's hosts — documented in theory.md's Assumptions and scope and the fixture JSON's own note, never fabricated.)
- [x] 22. Execute or wire the required variants: buffered read, warm mmap sequential, cold mmap sequential, random mapped access, mapped write plus flush.
- [x] 23. Add smoke, full and publication run commands through repository scripts. (Documented in `benchmark.md`; `.conf` wired for the unified runner.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (`.conf` declares `LAB_CPU_SET_REQUIREMENT=core_single`/exact cardinality, `LAB_LANGUAGES`, `EV_SELECTOR`, `LAB_PROFILER_POLICY`; wired into `config/benchmark-hosts/precision-5810.yaml` and `scripts/test-benchmark-batch.js` fixtures.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Inherited from the shared unified runner/correctness-gate mechanism — no per-lab override needed.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: ns/record, major/minor faults, read bandwidth, p99 latency, RSS/page cache, flush time. (Requires the native-Linux 5810 host run — including genuine `posix_fadvise`-based cold-cache disambiguation, unavailable on this dev machine.)
- [ ] 27. Capture representative evidence using supported tools from: JMH/custom harness, Criterion/custom harness, perf stat, page-fault counters, iostat where available. (Requires the native-Linux 5810 host run.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure — including the explicit cold-vs-warm limitation — are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, explicitly-labeled `perf stat` page-fault-counter example — never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Reviewed theory.md/java.md/rust.md/benchmark.md/exercises.md for Java-vs-Rust winner claims, unsupported claims, and reproducibility — none found; benchmark.md explicitly discloses that this dev run cannot demonstrate true page-cache cold-vs-warm behavior rather than presenting the close warm/cold numbers as if they refuted the hypothesis.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test --release` both green.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` and `npm run validate:curriculum` pass; no new a11y/consistency issues from this lab's own content.)
- [x] 40. Run `openspec validate plab-502-memory-mapped-files --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (`lab.json` status remains `draft`; benchmark.md's canonical section states "Awaiting native-Linux measurement".)
