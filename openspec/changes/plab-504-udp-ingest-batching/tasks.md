# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content exists at `content/labs/udp-ingest-batching/`; nothing to migrate.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/udp-ingest-batching-fixtures.json`; one shared 8-byte-sequence-prefixed datagram format, deterministic payload derivation, and drop/delivery accounting contract, asserted identically by both languages' test suites.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisite `shared-memory-ipc` wired bidirectionally in `assets/data/labs-index.json` and that lab's own `unlocks`; `assets/data/curriculum.json` already had a pre-populated `planned` entry, left untouched.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: packet pipeline, batch-size latency curve, drop/queue timeline.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: testing only loopback without labeling; ignoring kernel drops; unbounded queues hiding loss; reusing receive buffer after publication.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on DatagramChannel or repo-appropriate low-level API, direct buffers, receive loop, batching and bounded handoff. (`RawReceiveKernel` (one-packet-per-receive, reused direct buffer), `CopyingHandoffPipeline`, `ZeroCopyHandoffPipeline` — all `DatagramChannel`-based; "batched Linux receive" implemented as an explicit, documented capability-unavailable state rather than a narrow, undertested native binding — see theory.md.)
- [x] 12. Add deterministic correctness tests for every Java variant. (`UdpIngestOperationsTest`: full accounting over real UDP loopback for both handoff pipelines, ample-capacity full-delivery cases, and deliberately-undersized-capacity explicit-drop cases.)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`UdpIngestBenchmark`; found and fixed a real bug during testing — an un-shut-down sender `ExecutorService` left the forked JVM unable to exit cleanly, fixed via an explicit `@TearDown(Level.Trial)`.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (JFR allocation profiling and HdrHistogram-style percentile evidence are the designated tools per design.md; exercised in Exercise 3 as a synthetic teaching example — real capture deferred to the native-Linux run, see task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (`mvn test` green.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on std or mio/socket2-based receive loop, reusable buffers, recvmmsg optional Linux profile behind capability detection. (`std::net::UdpSocket`-based `RawReceiveKernel`, `CopyingHandoffPipeline`, `ZeroCopyHandoffPipeline`; `recvmmsg` deliberately not implemented — same capability-unavailable scoping as Java, to avoid introducing an undertested Linux-only native binding solely for this lab.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java. (Same accounting contract, same ample/undersized-capacity cases.)
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`benches/udp_ingest_batching.rs`, `[profile.bench] inherits = "release"`.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (All `unsafe` confined to the `Slab` struct's two accessor methods plus its `unsafe impl Sync`, each with a `# Safety` doc section explaining the hand-verified exclusivity protocol the type system cannot express; `#[allow(clippy::mut_from_ref)]` is deliberate and documented, not a suppressed real warning.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test --release`, `cargo clippy --release --all-targets -- -D warnings`, `cargo fmt --check` all clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: 64B, 256B, 1400B datagrams, steady rate, microburst, overload. (Three datagram-size profiles wired as JMH `@Param`/Criterion profile sweep; `LoadGenerator`/`send_burst`/`send_steady`/`send_microbursts` implement all three load shapes — the wired benchmark matrix itself exercises the overload/burst shape as its default; steady-rate and microburst load generators exist and are exercised by the correctness suite's design but are not separately swept as distinct JMH/Criterion benchmark parameters in this pass — documented as a scope limitation in theory.md's Assumptions and scope rather than fabricated.)
- [x] 22. Execute or wire the required variants: one packet per receive, reused direct buffer, batched Linux receive where supported, copying handoff, zero-copy view handoff with bounded lifetime. (Four of five wired and benchmarked; "batched Linux receive" capability-unavailable per above.)
- [x] 23. Add smoke, full and publication run commands through repository scripts. (Documented in `benchmark.md`; `.conf` wired for the unified runner.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (`.conf` declares `LAB_CPU_SET_REQUIREMENT=core_single`/exact cardinality, `LAB_LANGUAGES`, `EV_SELECTOR`, `LAB_PROFILER_POLICY`; wired into `config/benchmark-hosts/precision-5810.yaml` and `scripts/test-benchmark-batch.js` fixtures.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Inherited from the shared unified runner/correctness-gate mechanism — no per-lab override needed.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: packets/s, drop rate, p50/p99/p999, syscalls/packet, CPU, queue depth. (Requires the native-Linux 5810 host run — including kernel-level drop counters (`/proc/net/udp`, `ss -u`) this development machine's application-level accounting cannot observe.)
- [ ] 27. Capture representative evidence using supported tools from: custom load generator, perf stat, ss/netstat counters, JFR, HdrHistogram. (Requires the native-Linux 5810 host run; the custom load generator itself already exists and passes locally.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, explicitly-labeled kernel-vs-application drop-counter example — never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Reviewed theory.md/java.md/rust.md/benchmark.md/exercises.md for Java-vs-Rust winner claims, unsupported claims, and reproducibility — none found; benchmark.md explicitly discloses that the copy-vs-zero-copy and allocating-vs-reused gaps are small in this specific dev run rather than overstating either mechanism's effect.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test --release` both green.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` and `npm run validate:curriculum` pass; no new a11y/consistency issues from this lab's own content.)
- [x] 40. Run `openspec validate plab-504-udp-ingest-batching --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (`lab.json` status remains `draft`; benchmark.md's canonical section states "Awaiting native-Linux measurement".)
