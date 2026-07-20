# Benchmark harness traps — theory

## Performance question and hypothesis

**Question:** how can a benchmark report convincing numbers while measuring
almost nothing useful?

**Hypothesis:** dead-code elimination, constant folding, state leakage,
insufficient warm-up and incorrect parameterization can dominate the
result more than the code under test. A microbenchmark is a program the
optimizer is allowed to transform like any other program — when the
harness gives the compiler a provably unobservable or provably constant
computation, the compiler deletes or precomputes it, and the benchmark
times the leftovers.

**What would disprove it:** if the trap variants and the corrected
variants of the same kernel reported the same cost — a constant-folded
input costing the same as a runtime input, a benchmark with setup inside
the timed region costing the same as one with setup outside, a single-fork
run showing the same run-to-run spread as isolated forks — then the
harness would not matter and this lab's premise would be wrong. Every trap
in this lab is paired with a corrected variant of the *identical kernel*,
so each claim is separately falsifiable by measurement.

## Learning objective

Recognize invalid microbenchmarks, construct equivalent harnesses in JMH
and Criterion, and explain why raw nanoseconds alone — without harness
disclosure, uncertainty and correctness evidence — are insufficient to
support any performance claim.

## Prerequisites

- Basic Java and Rust reading fluency.
- Ability to run `mvn` and `cargo bench` locally.
- No prior JMH or Criterion experience is assumed.

## Pre-lab diagnostic

Before reading further, answer this: a colleague benchmarks
`sum += array[i]` over a million elements and reports **0.3 ns for the
whole loop**. A single memory access costs more than that. What are the
two most likely explanations, and what single change to the benchmark
would distinguish them?

(Answer at the end of this page — commit to yours first.)

## The mechanism: your benchmark is just a program

A JIT compiler (HotSpot's C2) and an ahead-of-time optimizer (LLVM behind
rustc) apply the same contract: they may perform any transformation that
preserves *observable behavior*. A benchmark harness is an ordinary caller
of your code, so:

- **Dead-code elimination (DCE).** If the result of the measured
  computation is never observed — not returned, not consumed, not stored
  anywhere visible — the whole computation has no observable behavior and
  may be removed. The timing loop then measures an empty method: loop
  overhead, nothing else.
- **Constant folding.** If every input is a compile-time constant, the
  compiler may evaluate any provable part of the computation once, at
  compile time. The benchmark then measures a load of a precomputed
  constant. Folding depends on what the compiler can *prove*: a scalar
  expression over literals folds readily; a heap array's contents
  generally do not — which is why this lab measures the folding contrast
  on the scalar kernel and shows on other kernels how the effect fades as
  provability drops.
- **Warm-up and tiered compilation.** The first executions of a Java
  method run interpreted, then in a quick tier, then — after enough
  profiling — in the optimizing tier, possibly replacing the code
  mid-loop (on-stack replacement). Numbers taken before compilation
  settles measure a moving target. Rust has no JIT warm-up, but caches,
  branch predictors and frequency governors still need their own settling
  time in both languages.
- **State leakage.** If one invocation mutates state the next invocation
  reads — a counter that keeps counting, a collection that keeps growing —
  every sample measures a *different* problem. The run may even look
  stable while measuring something the experiment never defined.
- **Parameterization.** A harness invoked with the wrong parameters
  measures the wrong experiment with perfect confidence: a "parser
  benchmark" that rebuilds its input every sample is an input-generation
  benchmark; a comparison where one side runs a debug build is a compiler
  settings comparison.
- **Process isolation.** JVM runs share JIT decisions, heap layout and
  profile pollution within a process. JMH's forks exist because two
  benchmarks in one JVM can influence each other; a single-fork run hides
  that run-to-run variance instead of reporting it.

## Visualization 1: benchmark lifecycle timeline

Deterministic model of what executes when a JMH benchmark runs (textual
form — each row is a phase; only the last column is ever reported):

| Phase | What runs | In the reported number? |
|---|---|---|
| JVM start, class loading | classloader, verifier | no |
| Trial setup (`@Setup(Level.Trial)`) | dataset generation, pinning | no |
| Warm-up iterations | interpreted → C1 → C2 transitions | no (discarded) |
| Measurement iterations | steady-state compiled code | **yes** |
| Iteration/trial teardown | validation, counters | no |
| Fork boundary | new JVM, all of the above again | new sample of everything |

The trap variants move work across these boundaries: `setupInsideTimed`
drags row 2 into row 4; a zero-warm-up run drags row 3 into row 4; a
single-fork run collapses the last row to one sample.

## Visualization 2: warm-up convergence (deterministic model)

This is a *model* of tiered compilation, not a measurement — the numbers
follow the documented tier thresholds, and the lab's warm-up experiments
let you produce the real curve on your own machine:

| Iteration | Executing tier | Modeled cost vs steady state |
|---|---|---|
| 1 | interpreter | ~20–50× |
| 2–3 | C1 (quick tier) | ~2–5× |
| 4–5 | C2 compiling in background | 1–2× (mixed) |
| 6+ | C2 steady state | 1× (what you meant to measure) |

The corrected profile discards iterations until the reported cost stops
drifting; the trap is averaging rows 1–5 into the result.

## Visualization 3: misleading vs corrected (structure of the comparison)

Qualitative shape of every trap/corrected pair in this lab (the measured
values come only from imported native-Linux evidence — see benchmark.md):

| Pair | Trap variant reports | Corrected variant reports |
|---|---|---|
| folded vs runtime input | sub-nanosecond "cost" — physically implausible for the work | plausible cost that scales with `rounds` |
| discarded vs consumed result | near-zero after DCE (why it is not even a benchmark here) | cost of the actual kernel |
| setup inside vs outside | setup cost + kernel cost, attributed to the kernel | kernel cost alone |
| single fork vs isolated forks | one sample, spread invisible | fork-to-fork spread reported |

A reported number that is *implausible against first principles* (faster
than a cache access, faster than a branch) is the primary detection
signal — the lab's exercises train exactly that reflex.

## Terminology

- **DCE** — dead-code elimination; removal of computation with no
  observable effect.
- **Constant folding** — compile-time evaluation of expressions over
  values the compiler can prove constant.
- **Blackhole / `black_box`** — harness-provided sinks (JMH / Criterion)
  that make a value observable (or an input opaque) to defeat DCE and
  folding.
- **Warm-up** — discarded initial iterations that let compilation and
  caches settle.
- **OSR** — on-stack replacement; swapping a running loop onto newly
  compiled code mid-execution.
- **Fork** — an isolated JVM process per measurement trial in JMH.
- **Steady state** — the regime where per-operation cost has stopped
  drifting; the only regime a throughput microbenchmark should report.

## Assumptions and scope

- Single-threaded kernels on one pinned core; concurrency traps
  (coordination, contention) belong to later labs.
- Both languages run optimizing compilers (HotSpot C2, LLVM `-O3` via the
  release profile); benchmarking a debug/interpreted build is treated as
  invalid input, not a data point.
- JMH's and Criterion's *defaults differ by design* (iteration model,
  sample counts, statistics). This lab always states the harness settings
  next to any number; it never compares the two harnesses' defaults as if
  they were the same instrument.

## The four traps this lab measures

1. **Benchmarking debug builds** — the Rust project pins
   `[profile.bench] inherits = "release"`, and the runner rejects debug
   builds; the contrast is taught, not published as evidence.
2. **Sharing mutable state between iterations** — the stateful-counter
   dataset exists precisely to show leakage; the corrected variant resets
   state per invocation and proves it against the shared fixture.
3. **Comparing default JMH and default Criterion settings as if
   identical** — all published settings are explicit; cross-harness
   numbers are presented as separate instruments, never one ranking.
4. **Publishing only the best run** — the evidence pipeline imports every
   fork/sample with uncertainty; a best-of-N number has no importable
   provenance and is rejected by the content gate.

## Pre-lab diagnostic — answer

The two likely explanations: (1) the loop was dead-code-eliminated
(result never observed), or (2) the sum was constant-folded/vectorized
into a precomputed value (input provably constant). The single
distinguishing change: make the *input* opaque (runtime-provided) while
still consuming the result — if the time jumps to plausible, folding was
the culprit; if it only jumps once the result is consumed, DCE was. The
lab's `foldedInput`/`runtimeInput` and result-sink variants are exactly
this experiment, run under controlled conditions.
