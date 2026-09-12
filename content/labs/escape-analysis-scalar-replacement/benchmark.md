# Escape analysis and scalar replacement — benchmark methodology

## Canonical results

**Awaiting native-Linux measurement.** The implementation and correctness
gates exist, but no canonical evidence from the dedicated native-Linux
benchmark host has been imported for this laboratory yet — no verified
performance conclusion is available. Development-machine runs validate
wiring and correctness only and are never published as measured results
(`docs/measurement-environments.md`).

## What is deterministic vs what is measured

- **Deterministic** — every dataset's input generators and the
  correctness oracle: all five variants sum to the identical total for a
  given dataset (`code/fixtures/escape-analysis-scalar-replacement-fixtures.json`).
- **Measured** — B/op, allocations/op, ns/op, GC events and the assembly
  allocation path, for each of the 15 (variant × dataset) cells, only
  from the native-Linux evidence runner.

## Operation definitions (the contract)

| Measured artifact | One operation | Oracle |
|---|---|---|
| every (variant, dataset) cell | one pass over 1,000,000 elements, constructing one `Aggregate` per element via the variant's usage pattern, accumulating `x[i]+y[i]` | all five variants' totals agree for a given dataset |

Dataset generation and the correctness oracle both run in
`@Setup(Level.Trial)`, never in the measured method. Java (JMH, run with
`-prof gc`) is the measured side for this lab's publication numbers; the
Rust track runs the identical usage patterns through `cargo bench` /
`escape_analysis_evidence` as a separately disclosed instrument
(rust.md) — none of Rust's five matrix variants allocate at all, which
the runner's evidence must show as a contrast, never smooth into a
single cross-language number.

## Required metrics

B/op and allocations/op (`gc.alloc.rate.norm` from JMH's `-prof gc`,
appended to every variant's JMH invocation by this lab's conf), ns/op, GC
events (`gc.count`/`gc.time`), and the assembly allocation path
(`-prof perfasm`, showing whether a `new` actually lowers to a call into
the allocator or is elided entirely) — from JMH plus `perf stat` wrapping
the pinned worker process
(`scripts/performance-lab/labs/escape-analysis-scalar-replacement.conf`,
`LAB_PERF_EVENTS`). Smoke runs are wiring checks only and are never
publication-eligible.

## Profiler evidence

JMH's `-prof gc` is the primary, required evidence for this lab — B/op
answers the mechanism question directly. JFR allocation profiling and
`-XX:+PrintEscapeAnalysis`/`-XX:+PrintEliminateAllocations`
(capability-detected; require a debug or diagnostics-unlocked JVM build)
supplement it with the compiler's own stated decision. `-prof perfasm`
shows the actual compiled allocation path. Where a tool is unavailable it
is recorded as unavailable, never substituted
(`docs/measurement-environments.md`).

## A real finding from development wiring (dev-only, never published)

Running this lab's Java benchmark with `-prof gc` on this repository's
development machine showed `nonEscaping` at ~2 B/op (scalar-replaced) and
the other four variants at exactly 32,000,0xx B/op for 1,000,000
elements — but `identityObserved` ran roughly 15–20× slower than the
other three materialized variants despite an identical allocation rate.
This is the kind of finding this lab's evidence panel exists to surface:
allocation rate alone does not explain the full cost of an operation, and
a metric that looks flat (B/op) can hide a large ns/op gap with a
completely different cause (java.md).

## Raw data and reproduction

```bash
# Correctness gates (both languages) — run before trusting any timing:
cd content/labs/escape-analysis-scalar-replacement/code/java && mvn test
cd content/labs/escape-analysis-scalar-replacement/code/rust && cargo test

# Dev wiring checks (never publication-eligible):
mvn -q -DskipTests package
java -jar target/benchmarks.jar 'EscapeAnalysisBenchmark' -f 1 -wi 3 -w 1s -i 5 -r 1s -prof gc -foe true
cargo bench --bench escape_analysis

# Publication evidence (dedicated native-Linux host only; see
# docs/linux-evidence-runner.md for host setup and CPU selection):
./scripts/performance-lab/run-linux-evidence.sh escape-analysis-scalar-replacement \
  --profile publication --cpus <CPU_A>

# Focused single-variant rerun (diagnosis):
./scripts/performance-lab/run-linux-evidence.sh escape-analysis-scalar-replacement \
  --profile smoke --cpus <CPU_A> --variant identity-parser
```

Raw JMH JSON (including the `-prof gc` section), perf stat CSVs,
placement evidence and environment metadata are produced per variant by
the runner and imported through the canonical result pipeline — numbers
are never transcribed into this page by hand.

## Limitations

- Whether a given allocation is actually eliminated is a property of the
  measured JDK build, not a portable language guarantee; every
  conclusion here is scoped to the disclosed toolchain version.
- "assuming every new allocates" and "using disabled EA as production
  recommendation" are named traps this page's evidence directly guards
  against — B/op is the checked fact, not an assumption from source code
  alone, and no conclusion here recommends disabling escape analysis in
  production (a diagnostic flag, never a deployment setting).
- "comparing stack Rust value to heap Java object with different
  semantics" is a named trap: Rust's five matrix variants and Java's five
  matrix variants are never merged into one ranking — only `sum_boxed`
  (rust.md), which is not part of the correctness matrix, is ever
  positioned next to Java's materialized numbers.
- No result on this page is a Java-versus-Rust performance claim
  (non-goal by policy).
