# Benchmark platform inventory (plab-002 baseline)

Snapshot of what actually exists on `main` before the reproducible-benchmark
platform lands, per `design.md`'s "Repository reality beats guessed
filenames" rule. Re-derive this from the code if it drifts — do not
hand-edit it into fiction. Verified against a live `mvn package` / `java
-jar` / `cargo test` / `cargo bench` run on this machine while writing this
document (OpenJDK 26.0.1, Apache Maven via sdkman, rustc/cargo 1.88.0).

## The 7 lab code projects

Every stable lab with `lab.json#/benchmark: true` or Java/Rust code ships a
`content/labs/<id>/code/java/` (Maven) and/or `content/labs/<id>/code/rust/`
(Cargo) project: `cache-hierarchy`, `false-sharing`, `mesi`,
`memory-ordering`, `cas-contention`, `spsc-ring-buffer`, `thread-per-core`.

All 7 are consistent today:

| Aspect | Java | Rust |
|---|---|---|
| Toolchain | `maven.compiler.source`/`target` = `21` (not `release` — conflicts with the `--add-exports` flag some labs need) | `edition = "2021"` |
| Harness | JMH `1.37` (`jmh.version` property) | `criterion = "0.5"` (dev-dependency) |
| Packaging | `maven-shade-plugin` → `target/benchmarks.jar` (runnable JMH uber-jar) | `[[bench]] harness = false` → `cargo bench` |
| README | "Not part of this site's own build or CI — a standalone project you clone/copy out and run on your own machine." | Same wording |
| `.gitignore` | `target/` (+ `dependency-reduced-pom.xml`) | `/target` |

No pom or Cargo.toml deviates from this table — checked with
`grep -rH` across all 7 of each file.

**Measured `benchmark.md` provenance** (all 5 labs with a benchmark section:
`cache-hierarchy`, `false-sharing`, `cas-contention`, `spsc-ring-buffer`,
`thread-per-core`) was captured on the same disclosed rig: JMH 1.37, OpenJDK
26.0.1 (HotSpot), Apple M1 Max, macOS, arm64; Criterion 0.5.1, rustc 1.88.0.
That happens to be *exactly* this development machine's toolchain, which is
why the smoke commands below reproduce cleanly here.

## Correctness test coverage gap (real, not fabricated)

| Lab | Java tests (`src/test/java`) | Rust tests (`#[test]` in `src/lib.rs`) |
|---|---|---|
| `cache-hierarchy` | none | present |
| `cas-contention` | none | present |
| `false-sharing` | none | present |
| `mesi` | present | present |
| `memory-ordering` | present | present |
| `spsc-ring-buffer` | present | present |
| `thread-per-core` | present | present |

Rust side is fully covered; three labs (`cache-hierarchy`, `cas-contention`,
`false-sharing`) have no Java-side correctness test today. This is a
pre-existing gap this change does not silently paper over: the correctness
gate (`scripts/benchmark-platform/correctness-gate.js`) reports it as
`missing` per language/lab rather than treating "no test files" as "tests
passed."

## What did not exist before this change

- No repository-native toolchain pin (no `rust-toolchain.toml`, no Maven
  enforcer/toolchain check) — a contributor's ambient JDK/Rust could silently
  drift from what a `benchmark.md` disclosure claims.
- No shared run-profile definition — every lab's README hand-picks JMH/
  Criterion flags ad hoc (see e.g. `-f 1 -wi 3 -i 5 -r 1s` in
  `false-sharing`'s disclosure vs. whatever the next lab's author chooses).
- No environment-metadata capture tool — host facts in `benchmark.md` are
  typed by hand into prose, not machine-captured.
- No run-id or immutable raw-artifact convention — raw JMH JSON / Criterion
  CSV output is documented as something a *reader* can regenerate, never
  committed or hashed.
- No comparability validator — nothing detects a debug build, mismatched
  dataset, or missing warm-up config before a number reaches a page.
- No CI benchmark job of any kind — `.github/workflows/ci.yml` only runs the
  Node-based site-content quality gates; `mvn`/`cargo` never execute in CI.
- No x86_64 evidence — the disclosed rig is arm64 only. `ubuntu-latest`
  GitHub Actions runners are x86_64, so a CI smoke job gives free, honest
  cross-architecture wiring coverage without claiming publication-grade
  numbers from a shared runner (design.md's CI policy).

This document is the plab-002 analogue of `docs/performance-lab-inventory.md`
(plab-001's baseline) — read that one for routes/content/curriculum state,
this one for the benchmark toolchain/tooling state it builds on.

## Cross-architecture validation (task 11)

The disclosed `benchmark.md` rig and this document's own capture were done
on arm64 (Apple M1 Max, macOS) — real hardware, not emulated. To validate
the platform tooling's Linux/x86_64 code paths without owning a second
physical machine, the same tooling was run under real x86_64 emulation
(Docker Desktop's QEMU backend, `--platform linux/amd64`) against the
`false-sharing` pilot lab:

- `node scripts/benchmark-platform/capture-env.js` inside `node:22-bookworm`
  (linux/x64) correctly exercised the Linux-only branches that never run on
  this arm64/darwin machine: `captureCoreTopology`'s `lscpu-raw` path (arm64
  reports `apple-silicon-perflevels` instead) and `captureGovernor`'s
  `"unavailable"` result (the emulated container has no `cpufreq` driver —
  correctly reported as unavailable, not fabricated as a real governor
  value; darwin instead reports `"not-applicable"` — the two capability
  states this module is designed to distinguish).
- `node scripts/benchmark-platform/run-correctness-gate.js false-sharing`,
  run for real inside the same x86_64 container with a `rustup`-installed
  Rust 1.88.0, produced the identical result shape as the arm64 run: Rust
  `"passed"` (2 tests), Java `"missing"` (documented gap), overall `"gap"`.
- `cargo bench --bench false_sharing -- --quick --noplot` (the `smoke`
  profile) completed successfully under emulation inside `rust:1.88`
  (linux/x64) — several times slower in wall-clock terms than the native
  arm64 run, exactly as expected for QEMU-emulated instructions, and exactly
  why `smoke` numbers carry zero statistical value regardless of host.

Real x86_64 hardware evidence — as opposed to QEMU emulation — comes from
the `benchmark-smoke` CI job (`.github/workflows/ci.yml`), which runs on
GitHub's actual x86_64 `ubuntu-latest` runners on every push/PR.

No profile is unsupported on either architecture today; nothing in this
platform's tooling makes an architecture-specific assumption beyond the two
explicitly capability-detected ones above (core topology, power
management).
