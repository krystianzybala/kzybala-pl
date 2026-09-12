# Inlining and call-site shape — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every dataset's input generator, the shared six-
  strategy pool, and the pairwise correctness oracle:
  `monomorphic == oversizedCallee` and `megamorphic == switchDispatch`
  (`code/fixtures/inlining-call-site-shape-fixtures.json`).
- **Measured** — ns/call, instructions, code size, inlining decisions and
  i-cache misses, for each of the 15 (variant × dataset) cells, only from
  the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| every (variant, dataset) cell | one pass over 1,000,000 inputs, dispatching to a strategy per element via the variant's mechanism, accumulating a wrapping sum | `monomorphic`/`oversizedCallee` totals agree; `megamorphic`/`switchDispatch` totals agree; `bimorphic` is checked against its own closed-form total |

Dataset generation and the correctness oracle both run in
`@Setup(Level.Trial)`, never in the measured method. Java (JMH) is the
measured side for this lab's publication numbers; the Rust track runs the
identical operations through `cargo bench` / `inlining_evidence` as a
separately disclosed instrument (rust.md) — no cross-harness ranking is
published, and Rust's numbers are explicitly not expected to reproduce
Java's inlining-driven shape (rust.md's `dyn Trait` note).

## Required metrics

ns/call, instructions, code size (compiled-method size where the
profiler/JIT exposes it), inlining decisions (`-XX:+PrintInlining` /
JITWatch on the Java side; `cargo asm` / LLVM remarks on the Rust side)
and i-cache misses — from `perf stat` wrapping the pinned worker process
(`scripts/performance-lab/labs/inlining-call-site-shape.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler evidence

`-prof perfasm` (JMH) shows the actual compiled dispatch sequence per
variant — the direct way to confirm whether a call was inlined or left as
a vtable call, rather than inferring it from timing alone. JITWatch
(capability-detected; requires JIT logging enabled) gives a structured
inlining-decision log for the Java side. `cargo asm` / LLVM optimization
remarks (capability-detected; some require a nightly toolchain) serve the
same purpose for Rust. Where a tool is unavailable it is recorded as
unavailable, never substituted (`docs/measurement-environments.md`).

## The magnitude of the mono→bimorphic→megamorphic gap is host- and JIT-version-dependent

Development-machine wiring runs during this lab's construction showed a
real, substantial gap across `monomorphic`/`bimorphic`/`megamorphic` on
the Java side (each roughly 3× the previous), and a smaller but still
measurable gap on the Rust `dyn Trait` side despite Rust never inlining
through any of them (see rust.md — indirect-branch-predictor pressure
scaling with target diversity is the leading candidate, not compiler
inlining). Exact ratios are expected to vary by JDK build, LLVM version,
and host microarchitecture; the qualitative direction (more distinct
concrete types → higher per-call cost) is the claim this lab's real
evidence checks, not a specific multiplier.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/inlining-call-site-shape/code/java && mvn test
cd content/labs/inlining-call-site-shape/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar 'InliningBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true
cargo bench --bench inlining

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh inlining-call-site-shape \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh inlining-call-site-shape \
  --profile smoke --cpus <CPU_A> --variant mega-codec
```

Raw JMH JSON, perf stat CSVs, placement evidence and environment metadata
are produced per variant by the runner and imported through the canonical
result pipeline — numbers are never transcribed into this page by hand.

## Limitations

- Whether a given call site is actually inlined is a property of the
  measured JDK build/LLVM version, not a portable language guarantee;
  every conclusion here is scoped to the disclosed toolchain versions.
- "manual devirtualization without maintainability discussion" is a named
  trap: `switchDispatch`'s speed, where it wins, is never presented
  without acknowledging the real-world cost of hand-maintaining a closed
  dispatch table as the strategy set grows.
- "forcing inline everywhere" is a named trap: this lab never concludes
  that maximizing inlining is free — `benchmark.md`'s i-cache-miss
  counters are exactly what would surface the code-cache-pressure cost a
  synthetic microbenchmark's small working set of code cannot reproduce
  on its own.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); Rust's `dyn Trait` variants measure a
  structurally different mechanism than Java's interface calls (rust.md)
  and are never merged into one ranking.
