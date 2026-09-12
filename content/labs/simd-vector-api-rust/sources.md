# SIMD: Java Vector API and Rust — sources

## Prerequisite labs

- [Cache locality and working-set size](/lab/cache-locality-working-set/) —
  bandwidth-bound vs. compute-bound access patterns, applied here at
  vector width.
- [Bounds checks and loop shape](/lab/bounds-checks-loop-shape/) —
  counted-loop shape sensitivity, directly relevant to
  `autoVectorizedCandidate`.

## Java Vector API

- [JEP 448: Vector API (Sixth Incubator)](https://openjdk.org/jeps/448)
- [`jdk.incubator.vector` package documentation](https://download.java.net/java/early_access/jdk-panama/docs/api/jdk.incubator.vector/jdk/incubator/vector/package-summary.html)
- [JDK Enhancement Proposal index: Vector API](https://openjdk.org/projects/panama/)

## Rust architecture intrinsics

- [`std::arch` module documentation](https://doc.rust-lang.org/std/arch/index.html)
- [`std::arch::aarch64` (NEON)](https://doc.rust-lang.org/std/arch/aarch64/index.html)
- [`std::arch::x86_64` (SSE2 and later)](https://doc.rust-lang.org/std/arch/x86_64/index.html)
- [`std::simd` (portable SIMD, nightly-only — why this lab does not use it)](https://doc.rust-lang.org/std/simd/index.html)
- [Rust RFC 2977: `std::arch` intrinsics stabilization](https://rust-lang.github.io/rfcs/2977-restrict-implicit-unsafe-blocks.html)

## Profiling and evidence tools

- [`perf stat` manual page](https://man7.org/linux/man-pages/man1/perf-stat.1.html)
- [JMH `-prof perfasm`](https://github.com/openjdk/jmh)
- [`cargo asm` (Rust disassembly inspection)](https://github.com/pacak/cargo-show-asm)

## Repository methodology

- `docs/measurement-environments.md` — dev-vs-publication result policy
  applied throughout java.md, rust.md and benchmark.md.
- `docs/linux-evidence-runner.md` — native-Linux publication host setup
  and CPU pinning, referenced by benchmark.md's reproduction commands.
- `openspec/changes/plab-306-simd-vector-api-rust/design.md` — this
  lab's own design rationale, including the "no unrelated frameworks or
  dependencies" constraint motivating the `std::arch`-over-external-crate
  choice (rust.md).
