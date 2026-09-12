# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats. (Confirmed the existing FFM downcall precedent in this repository — `CpuAffinity.java`/`NativeSleep.java` in other labs — before designing this lab's own `SymbolLookup.libraryLookup`-based pattern for a repo-compiled cdylib.)
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content exists at `content/labs/java-rust-ffm-interop/`; nothing to migrate.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/java-rust-ffm-interop-fixtures.json`; one shared numeric-transform formula and fixed-record layout, asserted identically by both languages' test suites.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisite `ffm-memory-segments` wired bidirectionally in `assets/data/labs-index.json` and that lab's own `unlocks`; `assets/data/curriculum.json` already had a pre-populated `planned` entry, left untouched.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: ABI boundary diagram, call granularity crossover, lifetime/ownership map.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: letting panic unwind across FFI; passing invalid lifetimes; benchmarking debug Rust library; calling per field and blaming FFM; silently copying buffers.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on Linker, SymbolLookup, FunctionDescriptor, downcall handles, upcall stubs, MemorySegment transfer and arena lifetime. (`RustLibrary` (downcall handles + upcall stub construction, self-building the native library via `cargo build --release` if not already present), `FfmInteropOps` (all five JVM-side variants).)
- [x] 12. Add deterministic correctness tests for every Java variant. (`FfmInteropOperationsTest`: all four transform variants agree byte-for-byte, record validation counts corrupted records and rejects misaligned length, upcall delivers every element in order through two callback shapes — including real downcalls/upcalls into the compiled Rust library.)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`FfmInteropBenchmark`, one untimed warm-up downcall in `@Setup` so stub JIT compilation isn't attributed to the first measured sample.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (JFR method profiling is the designated tool per design.md; exercised in Exercise 3 as a synthetic teaching example — real capture deferred to the native-Linux run, see task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (`mvn test` green, including real FFI calls into the compiled Rust cdylib.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on cdylib/staticlib ABI surface, repr(C), panic containment, pointer/length contracts and callback storage. (`cdylib` crate exporting `ffm_transform_scalar`/`ffm_transform_batch`/`ffm_validate_records`/`ffm_transform_with_callback`, every entry point wrapped in `catch_unwind` + `std::process::abort()` on panic — no unwinding across the FFI boundary.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java. (Pure-function/FFI-wrapper agreement for every variant, in-place and out-of-place batch transforms, record validation including the misaligned-length error path, in-order callback delivery.)
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`benches/ffm_interop.rs` — the "pure Rust baseline," `[profile.bench] inherits = "release"`.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (`unsafe` confined to the three `extern "C"` functions that reconstruct slices from raw pointers (`ffm_transform_batch`, `ffm_validate_records`, `ffm_transform_with_callback`), each with a `# Safety` doc section stating the pointer/length contract the Java caller must uphold; miri is not run against this crate because cdylib FFI-boundary code calling into a real dynamically-loaded JVM downcall/upcall path is outside miri's supported execution model — capability-detected as not applicable rather than silently skipped.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test --release`, `cargo clippy --release --all-targets -- -D warnings`, `cargo fmt --check` all clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: numeric transform, fixed-record validation, callback notification. (Numeric transform fully implemented across all six variants; fixed-record validation and callback notification reuse the same variant infrastructure (batched/zero-copy downcalls, the upcall variant) rather than being swept as a fully independent benchmark matrix — documented as a scope limitation in theory.md's Assumptions and scope rather than fabricated.)
- [x] 22. Execute or wire the required variants: pure Java baseline, pure Rust baseline, scalar downcall per item, batched downcall, zero-copy buffer downcall, Rust-to-Java upcall. (All six implemented — five benchmarked within JMH, pure Rust baseline measured separately in Criterion, per design.md's own non-goal against a combined Java/Rust ranking.)
- [x] 23. Add smoke, full and publication run commands through repository scripts. (Documented in `benchmark.md`; `.conf` wired for the unified runner.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (`.conf` declares `LAB_CPU_SET_REQUIREMENT=core_single`/exact cardinality, `LAB_LANGUAGES`, `EV_SELECTOR`, `LAB_PROFILER_POLICY`; wired into `config/benchmark-hosts/precision-5810.yaml` and `scripts/test-benchmark-batch.js` fixtures.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Rust `Cargo.toml` pins the release profile for the library and benches; inherited from the shared unified runner/correctness-gate mechanism otherwise.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: ns/call, ns/item, calls/message, bytes copied, allocation rate, p99 callback latency. (Requires the native-Linux 5810 host run.)
- [ ] 27. Capture representative evidence using supported tools from: JMH, Criterion, JFR, perf, perfasm, cargo asm, sanitizers/miri where applicable. (Requires the native-Linux 5810 host run; miri is capability-unavailable for this crate regardless — see task 19.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, explicitly-labeled JFR method-profiling-sample example — never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Reviewed theory.md/java.md/rust.md/benchmark.md/exercises.md for Java-vs-Rust winner claims, unsupported claims, and reproducibility — none found; benchmark.md explicitly keeps the pure-Java and pure-Rust baselines separate and never combines them into one ranking, per design.md's non-goal.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test --release` both green — including real downcalls/upcalls into the compiled Rust cdylib.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` and `npm run validate:curriculum` pass; no new a11y/consistency issues from this lab's own content.)
- [x] 40. Run `openspec validate plab-506-java-rust-ffm-interop --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (`lab.json` status remains `draft`; benchmark.md's canonical section states "Awaiting native-Linux measurement".)
