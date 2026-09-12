# Tasks

## 1. Discovery and Contract

- [x] 1. Inspect repository structure, current lab content, build tools and result formats.
- [x] 2. For an existing lab, inventory routes, content, benchmark variants and raw assets before editing. (Greenfield lab — no prior route/content exists at `content/labs/fixed-binary-serialization/`; nothing to migrate.)
- [x] 3. Write the Java/Rust semantic equivalence contract and shared correctness fixtures. (`code/fixtures/fixed-binary-serialization-fixtures.json`; one shared `EventRecord`/`Event` shape, fixed 70-byte little-endian layout, asserted identically by both languages' test suites.)
- [x] 4. Confirm the experiment matrix, supported architectures and optional evidence tools.
- [x] 5. Add the lab metadata entry, prerequisites and status as planned/partial only. (`lab.json` status `draft`; prerequisite `struct-layout-alignment` wired bidirectionally in `assets/data/labs-index.json` and `content/labs/struct-layout-alignment/lab.json`'s `unlocks`; `assets/data/curriculum.json` already had a pre-populated `planned` entry, left untouched.)

## 2. Educational Content

- [x] 6. Write the theory section around the performance mechanism, not around a language winner.
- [x] 7. Create the required visual explanations: wire layout explorer, encode/decode pipeline, size-latency trade-off.
- [x] 8. Add terminology, assumptions and a short pre-lab diagnostic question.
- [x] 9. Document the known traps: comparing incompatible schemas; excluding validation; using compressed generic formats without reporting CPU; hardcoding endianness silently.
- [x] 10. Add accessible textual/table fallbacks for every essential visualization.

## 3. Java Implementation

- [x] 11. Implement the Java baseline and required variants with focus on handwritten fixed layout, FFM buffer access, ByteBuffer baseline and one representative generic serializer already present in repo if any. (`GenericObjectCodec` (JDK `ObjectOutputStream`/`ObjectInputStream` — the one generic serializer already available without a new dependency), `ByteBufferCodec`, `FfmFlyweightCodec`/`EventView`.)
- [x] 12. Add deterministic correctness tests for every Java variant. (`BinSerOperationsTest`: round-trip for all 3 codecs x 4 profiles, fixed `WIRE_SIZE`, byte-for-byte ByteBuffer/FFM wire equality, versioned-optional-field always-readable-slot behavior.)
- [x] 13. Add JMH or the appropriate macro harness with setup outside timing by default. (`BinSerBenchmark`, `@Param` over all 4 profiles, setup in `@Setup`.)
- [x] 14. Add allocation, JVM-flag and profiler hooks relevant to the lab. (JFR allocation profiling is the designated tool per design.md; exercised in Exercise 3 as a synthetic teaching example — real capture deferred to the native-Linux run, see task 27.)
- [x] 15. Run formatting, static analysis and Java tests before accepting benchmark output. (`mvn test` green.)

## 4. Rust Implementation

- [x] 16. Implement the Rust baseline and required variants with focus on manual encoding, byteorder-style helpers or existing lightweight codec, borrowed decode views. (`encode`/`decode_owned` (owned) and `EventView` (borrowed, zero-copy) in `src/lib.rs`, identical wire layout to Java.)
- [x] 17. Add deterministic correctness tests using the same fixtures as Java. (Hard-coded fixtures matching `BinSerFixtures`/the shared JSON, same 4 profiles.)
- [x] 18. Add Criterion or the appropriate macro harness using the pinned release profile. (`benches/fixed_binary_serialization.rs`, `[profile.bench] inherits = "release"`.)
- [x] 19. Isolate and document unsafe code; add miri/fuzz/model checks where appropriate. (No `unsafe` in this crate — the borrowed view uses only safe slice indexing and `from_le_bytes`; nothing to isolate.)
- [x] 20. Run rustfmt, clippy and Rust tests before accepting benchmark output. (`cargo test --release`, `cargo clippy --release -- -D warnings`, `cargo fmt --check` all clean.)

## 5. Benchmark Matrix

- [x] 21. Add parameter coverage for: small command, medium event, repeated fields, versioned optional field. (All 4 profiles hard-coded as fixtures in both languages and wired as JMH `@Param`.)
- [x] 22. Execute or wire the required variants: generic object codec, ByteBuffer/manual codec, FFM flyweight codec, Rust owned decode, Rust borrowed decode.
- [x] 23. Add smoke, full and publication run commands through repository scripts. (Documented in `benchmark.md`; `.conf` wired for the unified runner.)
- [x] 24. Capture environment/toolchain metadata and comparability checks. (`.conf` declares `LAB_CPU_SET_REQUIREMENT=core_single`/exact cardinality, `LAB_LANGUAGES`, `EV_SELECTOR`, `LAB_PROFILER_POLICY`; wired into `config/benchmark-hosts/precision-5810.yaml` and `scripts/test-benchmark-batch.js` fixtures.)
- [x] 25. Reject debug builds, unequal parameters and missing correctness results. (Inherited from the shared unified runner/correctness-gate mechanism — no per-lab override needed.)

## 6. Evidence and Results

- [ ] 26. Capture these metrics: encode/decode ns/message, B/message, wire bytes, bytes copied, branch misses. (Requires the native-Linux 5810 host run.)
- [ ] 27. Capture representative evidence using supported tools from: JMH, Criterion, JFR allocation, perf, perfasm, cargo asm. (Requires the native-Linux 5810 host run.)
- [ ] 28. Preserve raw output and import it through the canonical result schema. (Requires imported native-Linux evidence.)
- [ ] 29. Generate deterministic tables/charts with uncertainty or distribution information. (Requires imported native-Linux evidence.)
- [ ] 30. Write conclusions separating observation, interpretation, limitation and inference. (Requires imported native-Linux evidence; benchmark.md's "awaiting-native-linux-measurement" section and illustrative-dev-run disclosure are already in place.)

## 7. Exercises and Review

- [x] 31. Add one diagnosis exercise with a deliberately flawed variant or result.
- [x] 32. Add one implementation exercise with measurable success criteria.
- [x] 33. Add one evidence-interpretation exercise using raw profiler/counter output. (Synthetic, explicitly-labeled JFR allocation-profile example — never real captured evidence.)
- [x] 34. Keep hints and solutions separate from the initial exercise statement.
- [x] 35. Perform editorial review for neutrality, reproducibility and unsupported claims. (Reviewed theory.md/java.md/rust.md/benchmark.md/exercises.md for Java-vs-Rust winner claims, unsupported claims, and reproducibility — none found; benchmark.md explicitly discloses the JMH FFM smoke numbers' high variance rather than presenting them as trustworthy.)

## 8. Verification and Publication

- [x] 36. Run all Java and Rust correctness tests. (`mvn test` and `cargo test --release` both green.)
- [ ] 37. Run the smoke profile and at least one full controlled result set. (Smoke run executed locally on this dev machine for both languages — illustrative numbers captured in benchmark.md — but the native-Linux smoke/full profile via `run-linux-evidence.sh` requires the 5810 host.)
- [ ] 38. Verify raw artifact links, environment metadata and result provenance. (Requires imported native-Linux evidence to verify against.)
- [x] 39. Verify accessibility, route behavior and responsive presentation. (`node scripts/validate-labs.js` -> 30/30 OK; `npm run validate:curriculum` -> 41 OK; no new a11y/consistency issues from this lab's own content.)
- [x] 40. Run `openspec validate plab-501-fixed-binary-serialization --strict` and repository quality gates.
- [x] 41. Promote evidence maturity only to the level actually achieved; do not fake completion. (`lab.json` status remains `draft`; benchmark.md's canonical section states "Awaiting native-Linux measurement".)
