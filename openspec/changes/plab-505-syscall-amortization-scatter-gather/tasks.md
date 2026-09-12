# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content exists at `content/labs/syscall-amortization-scatter-gather/`; nothing to migrate.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/syscall-amortization-scatter-gather-fixtures.json`; one shared 16-byte header (sequence + payloadLength) + deterministic payload wire format, and a write-call/partial-write accounting contract, asserted identically by both languages' test suites.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisite `udp-ingest-batching` wired bidirectionally in `assets/data/labs-index.json` and that lab's own `unlocks`; `assets/data/curriculum.json` already had a pre-populated `planned` entry, left untouched.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: user/kernel transition diagram, batch composition timeline, latency vs syscalls chart.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: ignoring partial writes; counting application calls instead of syscalls; letting Nagle/coalescing differ silently; measuring only throughput.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on gathering writes, direct buffers, batching policy and partial-write handling. (`Senders.singleSmallWrite`/`coalescedBuffer`/`scatterGatherWrite`/`sizeBoundedBatch`, all with correct partial-write loops; `Receiver.receiveSlowly` for the backpressure scenario.)
- [x] 12. Add deterministic correctness tests for every Java variant. (`SyscallAmortOperationsTest`: full delivery + exact write-call-count assertions per variant over real TCP loopback, plus an induced-backpressure/partial-write scenario.)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`SyscallAmortBenchmark`, receiver spawned in a background thread, executor shut down via `@TearDown`.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (strace summary and perf stat are the designated tools per design.md; exercised in Exercise 3 as a synthetic teaching example — real capture deferred to the native-Linux run, see task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (`mvn test` green.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on write_vectored, reusable IoSlice arrays, batching and partial-write state machine. (`write_vectored_all` rebuilds the `IoSlice` array from each buffer's own tracked offset every loop iteration — the explicit partial-write state machine design.md's Rust track calls for — used by `scatter_gather_write` and `size_bounded_batch`.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java. (Same wire format, same write-call-count expectations, same induced-backpressure scenario.)
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`benches/syscall_amortization_scatter_gather.rs`, `[profile.bench] inherits = "release"`.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (No `unsafe` in this crate — `write_vectored_all`'s partial-write handling uses only safe slice indexing and freshly-constructed `IoSlice`s; nothing to isolate.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test --release`, `cargo clippy --release --all-targets -- -D warnings`, `cargo fmt --check` all clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: header plus payload, small messages, mixed sizes. (Three dataset profiles wired as JMH `@Param`/Criterion profile sweep; `mixedSizes`'s full variable-payload-size sweep is exercised by the fixture/correctness suite, while the benchmark matrix uses one representative fixed size for its timed region — documented as a scope limitation in theory.md's Assumptions and scope rather than fabricated.)
- [x] 22. Execute or wire the required variants: single small write, coalesced buffer, scatter/gather write, size/time bounded batch, backpressured receiver. (Four wired and benchmarked as throughput comparisons; backpressured receiver implemented and verified as a correctness/resilience scenario, per its own nature, not a throughput benchmark — documented in benchmark.md. Time-bounded batching specifically is left as Exercise 2 rather than a fifth wired benchmark variant.)
- [x] 23. Add smoke, full and publication run commands through repository scripts. (Documented in `benchmark.md`; `.conf` wired for the unified runner.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (`.conf` declares `LAB_CPU_SET_REQUIREMENT=core_single`/exact cardinality, `LAB_LANGUAGES`, `EV_SELECTOR`, `LAB_PROFILER_POLICY`; wired into `config/benchmark-hosts/precision-5810.yaml` and `scripts/test-benchmark-batch.js` fixtures.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Inherited from the shared unified runner/correctness-gate mechanism — no per-lab override needed.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: syscalls/message, ns/message, throughput, p99 queue delay, bytes copied, partial writes. (Requires the native-Linux 5810 host run — including real `strace`-verified syscall counts this development machine's application-level `writeCalls` proxy cannot substitute for.)
- [ ] 27. Capture representative evidence using supported tools from: custom socket/file harness, strace summary, perf stat, JFR, Criterion support harness. (Requires the native-Linux 5810 host run; the custom socket harness itself already exists and passes locally.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure — including the disclosed Java/Rust batching discrepancy in this dev run — are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, explicitly-labeled `strace -c` summary example — never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Reviewed theory.md/java.md/rust.md/benchmark.md/exercises.md for Java-vs-Rust winner claims, unsupported claims, and reproducibility — none found; benchmark.md explicitly discloses the Java/Rust batching-advantage discrepancy in this specific dev run rather than picking whichever number looked cleaner.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test --release` both green.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` and `npm run validate:curriculum` pass; no new a11y/consistency issues from this lab's own content.)
- [x] 40. Run `openspec validate plab-505-syscall-amortization-scatter-gather --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (`lab.json` status remains `draft`; benchmark.md's canonical section states "Awaiting native-Linux measurement".)
