# Design: Java-Rust Interop with FFM Downcalls and Upcalls

## Context

This is a new lab; use the shared framework and result pipeline rather than creating a custom mini-site.

This laboratory belongs to **I/O & IPC**, level **Advanced**. Its purpose is not to manufacture a dramatic Java-versus-Rust chart. Its purpose is to expose the mechanism, build equivalent implementations, measure them under controlled parameters and teach the learner how to verify or reject the hypothesis.

## Performance Question

What is the real cost and safety boundary of calling Rust from Java and Java callbacks from Rust?

## Hypothesis

FFM removes JNI ceremony but not ABI, transition, pinning, lifetime or callback costs; coarse-grained calls can win while chatty FFI loses.

## Learning Outcomes

- design narrow stable ABI
- batch FFI work
- implement safe downcall/upcall lifetimes

## Experience Flow

1. **Theory** explains the hardware/runtime mechanism and vocabulary.
2. **Visualization** provides a deterministic or data-driven model: ABI boundary diagram, call granularity crossover, lifetime/ownership map.
3. **Java track** builds the baseline and optimized variants with focus on Linker, SymbolLookup, FunctionDescriptor, downcall handles, upcall stubs, MemorySegment transfer and arena lifetime.
4. **Rust track** builds equivalent variants with focus on cdylib/staticlib ABI surface, repr(C), panic containment, pointer/length contracts and callback storage.
5. **Correctness gate** executes shared fixtures before timing.
6. **Benchmark matrix** executes controlled variants and datasets.
7. **Evidence panel** links raw output, assembly/profiles/counters and environment metadata.
8. **Analysis** separates observation, interpretation, limitations and non-portable conclusions.
9. **Exercises** require diagnosis, modification and evidence-based explanation.

## Experiment Matrix

### Variants

- pure Java baseline
- pure Rust baseline
- scalar downcall per item
- batched downcall
- zero-copy buffer downcall
- Rust-to-Java upcall

### Datasets and Profiles

- numeric transform
- fixed-record validation
- callback notification

### Metrics

- ns/call
- ns/item
- calls/message
- bytes copied
- allocation rate
- p99 callback latency

### Evidence Tools

- JMH
- Criterion
- JFR
- perf
- perfasm
- cargo asm
- sanitizers/miri where applicable

The implementation must capability-detect optional tools. Missing `perf c2c`, affinity, NUMA or architecture-specific assembly support must mark evidence as unavailable rather than fail the ordinary content build.

## Java Track

- Use the repository Java toolchain; do not silently benchmark an IDE/debug configuration.
- Use JMH for microbenchmarks and a dedicated harness for cross-thread, process, network or coordinated-load experiments where JMH is not the right abstraction.
- Keep setup outside the timed region unless setup cost is the experiment.
- Consume results and use runtime-generated parameters to prevent dead-code elimination and constant folding.
- Capture allocations and JVM flags where relevant.
- Explain JIT warm-up, compilation and deoptimization when they affect the result.
- Primary focus: Linker, SymbolLookup, FunctionDescriptor, downcall handles, upcall stubs, MemorySegment transfer and arena lifetime.

## Rust Track

- Use the pinned release benchmark profile; debug builds are invalid for comparison.
- Use Criterion for microbenchmarks and an explicit harness for cross-thread/process/network experiments.
- Use `black_box` and separate setup from timing.
- Keep unsafe code isolated, documented and covered by correctness/fuzz/model checks where relevant.
- Do not weaken validation or ownership guarantees solely to beat Java.
- Primary focus: cdylib/staticlib ABI surface, repr(C), panic containment, pointer/length contracts and callback storage.

## Semantic Equivalence Contract

Before comparing implementations, define:

- exact input bytes/values and parameter generation,
- exact output and error behavior,
- integer overflow and floating-point semantics,
- validation, bounds and UTF-8 guarantees,
- allocation/lifetime ownership,
- threading and memory-order guarantees,
- inclusion or exclusion of setup, encoding, copying and cleanup.

A comparison may still be educational when semantics differ, but it must be labeled as a design trade-off rather than a language speed result.

## Measurement Procedure

1. Execute shared correctness fixtures.
2. Execute the smoke profile to validate wiring.
3. Capture host and toolchain metadata.
4. Stabilize the publication host using only documented, reversible steps.
5. Execute randomized or interleaved run order where practical to reduce thermal/time bias.
6. Preserve complete raw harness output.
7. Capture profiler/counter evidence for representative variants.
8. Re-run material findings and assign evidence maturity.
9. Import through the canonical result pipeline; never paste numbers into page source.

## Benchmark Traps and Guardrails

- letting panic unwind across FFI
- passing invalid lifetimes
- benchmarking debug Rust library
- calling per field and blaming FFM
- silently copying buffers

Additional guardrails:

- No best-of-N cherry-picking.
- No mean-only latency conclusion for bursty, concurrent or end-to-end workloads.
- No cross-machine direct comparison unless the page is explicitly about machines.
- No hidden changes to CPU affinity, governor, heap, allocator or compiler flags.
- No claim that absence of observed failure proves a memory-order algorithm correct.

## Result Presentation

The page must provide:

- a compact summary table,
- distribution/confidence information appropriate to the harness,
- raw artifact links,
- environment and command metadata,
- profiler/counter evidence,
- an explanation of why the result occurred,
- limitations and portability notes,
- a neutral Java/Rust conclusion.

## Failure and Unsupported Cases

Unsupported architecture features, missing profilers or restricted host tuning must produce an explicit `unsupported` or `evidence unavailable` state. They must not produce zero values, empty charts or fabricated fallback data.

## Rollback and Migration

For existing labs, keep old routes and assets until the shared-framework version passes parity review. For new labs, changes are additive and can be removed without affecting the reference labs or result schema.


## Measurement and evidence contract

Normalized 2026-07-15 against the canonical framework
(`docs/measurement-environments.md`, `docs/linux-evidence-runner.md`,
`docs/lab-framework.md`); framework mechanics are referenced, not repeated.

1. **Phenomenon under measurement** — What is the real cost and safety boundary of calling Rust from Java and Java callbacks from Rust?
2. **Primary hypothesis** — FFM removes JNI ceremony but not ABI, transition, pinning, lifetime or callback costs; coarse-grained calls can win while chatty FFI loses.
3. **Controlled variables** — the variant axis (pure Java baseline, pure Rust baseline, scalar downcall per item, batched downcall, zero-copy buffer downcall, Rust-to-Java upcall) and the
   dataset/profile axis (numeric transform, fixed-record validation, callback notification) are varied one at a time; toolchain,
   heap/allocator settings, CPU placement and dataset bytes are held fixed
   within any compared set.
4. **Java operation definition (contract)** — the implementation change
   must define exactly one benchmark operation per variant — a single unit
   of the variant's work over the declared dataset, with setup, dataset
   generation and validation outside the timed region — and record the
   exact definition in the lab's `benchmark.md`. JMH group/thread layout
   and any per-worker pinning follow the unified runner conventions.
5. **Rust operation definition (contract)** — identical unit of work,
   dataset bytes, payload widths, batching and worker lifecycle as the
   Java definition (persistent workers where Java uses persistent JMH
   threads — never spawn/join inside a measured sample compared against
   persistent workers); differences that cannot be reconciled must be
   published as separate scenarios, never merged into one comparison.
6. **Correctness oracle** — deterministic expected outputs for every
   variant (exact counts/results/invariants appropriate to this lab's
   operations), asserted by both languages' test suites before any timing
   is trusted; the unified correctness gate blocks measurement on failure.
7. **Semantic-equivalence fixture** — one shared fixture
   (`content/labs/<id>/code/fixtures/`, per
   `docs/benchmark-correctness-fixtures.md`) hard-coded identically by the
   Java and Rust suites; any intentional semantic difference is documented
   in the equivalence contract and excludes the affected pair from
   cross-language comparison (`comparison-guard.js`).
8. **Benchmark scenarios** — pure Java baseline, pure Rust baseline, scalar downcall per item, batched downcall, zero-copy buffer downcall, Rust-to-Java upcall × numeric transform, fixed-record validation, callback notification, each scenario
   selected per process invocation (never mixed in one run), named and
   recorded in the run's provenance.
9. **Expected canonical metrics** — ns/call, ns/item, calls/message, bytes copied, allocation rate, p99 callback latency; imported through the
   plab-003 canonical schema with units, uncertainty and provenance.
10. **Profiler evidence** — repository-supported subset of: JMH, Criterion, JFR, perf, perfasm, cargo asm, sanitizers/miri where applicable;
    unavailable tools are recorded as unavailable, never substituted.
11. **Common benchmark traps (must be taught and avoided)** —
- letting panic unwind across FFI
- passing invalid lifetimes
- benchmarking debug Rust library
- calling per field and blaming FFM
- silently copying buffers
12. **Invalid conclusions this laboratory must never publish** —
- any Java-versus-Rust winner claim (non-goal by policy)
- any publication-grade claim from a developer-workstation run
- any claim derived from a rejected or partial evidence run
- concluding anything from a run that exhibits: letting panic unwind across FFI
- concluding anything from a run that exhibits: passing invalid lifetimes
- concluding anything from a run that exhibits: benchmarking debug Rust library
- concluding anything from a run that exhibits: calling per field and blaming FFM
- concluding anything from a run that exhibits: silently copying buffers
13. **Native-Linux host requirements** — publication evidence is collected
    exclusively by `scripts/performance-lab/run-linux-evidence.sh` on the
    dedicated physical Linux host (explicit CPU sets validated against
    live topology; worker affinity where topology matters; unprivileged
    perf; normal-user execution) and batched via
    `run-all-benchmarks.sh`. Until imported and reviewed, the lab renders
    `awaiting-native-linux-measurement`.
14. **Public content outline** — performance question and hypothesis;
    theory/mechanism; visualization with textual fallback; Java track;
    Rust track; benchmark methodology (awaiting state pre-import);
    profiler evidence; traps/limitations; exercises (diagnosis +
    implementation with success criteria); sources — per the unified
    content contract enforced by `scripts/validate-labs.js`.
15. **Completion and verification gates** — the tasks.md completion gate
    plus: correctness suites green in both languages, runner configuration
    accepted by the batch preflight, `openspec validate plab-506-java-rust-ffm-interop --strict`,
    and evidence maturity claimed no higher than derived
    (`docs/evidence-maturity.md`). Learning outcomes: design narrow stable ABI; batch FFI work; implement safe downcall/upcall lifetimes.
