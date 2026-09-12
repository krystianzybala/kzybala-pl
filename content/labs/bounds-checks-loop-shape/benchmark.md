# Bounds checks and loop shape — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every dataset's backing payload (`backing[i] = i`),
  the shared access-order permutation (identical to
  content/labs/cache-locality-working-set's), and the correctness oracle:
  every variant on a given dataset sums to the identical closed-form
  total (`code/fixtures/bounds-checks-loop-shape-fixtures.json`).
- **Measured** — ns/element, instructions, branches, assembly-level
  bounds-check presence/absence, and vector width, for each of the 15
  (variant × dataset) cells, only from the native-Linux evidence runner.

## Operation definitions (the contract)

| Dataset | Backing size | One operation |
|---|---|---|
| primitiveArrays | N = 1,000,000 | one pass over the full backing array, per variant's access expression |
| slicesSubranges | 2N = 2,000,000 | one pass over the window `[500000, 1500000)` (Java: explicit bounds into the original array, except `safeIterator` which iterates a setup-time copy; Rust: a genuine `&[i32]` slice — see java.md/rust.md's equivalence-contract note) |
| stridedAccess | N·stride = 4,000,000 (stride=4) | one pass visiting `backing[i*stride]` for `i` in `[0, N)` |

Dataset generation, the permutation build, and the closed-form
correctness oracle all run in `@Setup(Level.Trial)`, never in the
measured method. Java (JMH) is the measured side for this lab's
publication numbers; the Rust track runs the identical operations
through `cargo bench` / `bounds_checks_evidence` as a separately
disclosed instrument (rust.md) — no cross-harness ranking is published.

## Required metrics

ns/element, instructions, branches, assembly checks (present/eliminated,
from `-prof perfasm` / `cargo asm`) and vector width — from `perf stat`
wrapping the pinned worker process
(`scripts/performance-lab/labs/bounds-checks-loop-shape.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler evidence

`-prof perfasm` (JMH) and `cargo asm` (Rust) capture the actual compiled
sequence for representative cells, showing whether a bounds check
survived optimization; `-XX` diagnostics (`-XX:+PrintCompilation` and
related flags, capability-detected — some require a debug JVM build) and
LLVM optimization remarks (capability-detected — some require a nightly
toolchain) supplement this where the host supports them. Where a tool is
unavailable it is recorded as unavailable, never substituted
(`docs/measurement-environments.md`).

## The RCE-defeat effect is host- and JIT-version-dependent

Development-machine wiring runs during this lab's construction showed the
`canonical` and `opaqueLimit` variants performing nearly identically on
one JDK build, and a clear, real, well-known-mechanism gap between them
in other configurations. This is expected, not a defect: whether a given
optimizer version successfully proves (or fails to prove) a specific loop
shape range-safe is an empirical property of that specific compiler
build, not a language guarantee — which is exactly why this lab's
required evidence is the real assembly/counters from the native-Linux
host, never an assumption from the theory page's textbook model alone.

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/bounds-checks-loop-shape/code/java && mvn test
cd content/labs/bounds-checks-loop-shape/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar 'BoundsChecksBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -foe true
cargo bench --bench bounds_checks

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh bounds-checks-loop-shape \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh bounds-checks-loop-shape \
  --profile smoke --cpus <CPU_A> --variant opaque-strided
```

Raw JMH JSON, perf stat CSVs, placement evidence and environment metadata
are produced per variant by the runner and imported through the canonical
result pipeline — numbers are never transcribed into this page by hand.

## Limitations

- Whether the JIT/LLVM actually eliminates a given check is a property of
  the specific compiler build measured, not a portable guarantee — every
  conclusion here is scoped to the measured toolchain versions, disclosed
  in the run's environment metadata.
- `unchecked`'s numbers are never presented as a validation-free
  improvement over the checked variants without also stating what safety
  guarantee was traded away — "benchmarking different validation
  guarantees" is a named trap this lab explicitly guards against.
- `irregularIndex`'s cost mixes check-elimination failure with cache-
  locality effects (its access pattern is the identical permutation used
  in content/labs/cache-locality-working-set); conclusions attribute cost
  to one or the other only when the specific counter (branches vs.
  cache-misses) supports it.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy); Rust's genuine zero-copy slice type for
  `slicesSubranges` is a real language-capability difference from Java,
  documented as a semantic-equivalence note (java.md, rust.md), not
  smoothed into a single number.
