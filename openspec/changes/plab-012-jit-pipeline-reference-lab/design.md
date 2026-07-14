# Design: JIT Pipeline: Interpretation, C1, C2 and Deoptimization

## Context

The repository already has a version of this lab. Begin with an inventory, preserve routes/assets, and migrate incrementally.

This laboratory belongs to **JVM Runtime**, level **Reference**. Its purpose is not to manufacture a dramatic Java-versus-Rust chart. Its purpose is to expose the mechanism, build equivalent implementations, measure them under controlled parameters and teach the learner how to verify or reject the hypothesis.

## Performance Question

How does Java code move from bytecode through tiered compilation, and why do benchmark results change during warm-up?

## Hypothesis

Tiered compilation improves steady-state throughput but introduces phase changes, recompilation and deoptimization that must be visible in any honest Java/Rust comparison.

## Learning Outcomes

- explain tiered compilation
- separate startup from steady state
- interpret compilation and deoptimization evidence

## Experience Flow

1. **Theory** explains the hardware/runtime mechanism and vocabulary.
2. **Visualization** provides a deterministic or data-driven model: tiered compilation timeline, latency-by-iteration plot, deoptimization event overlay.
3. **Java track** builds the baseline and optimized variants with focus on Bytecode, interpreter, C1/C2 tiers, compilation logs, code cache and deoptimization.
4. **Rust track** builds equivalent variants with focus on AOT compilation as the contrast: optimization levels, monomorphization and code generation before execution.
5. **Correctness gate** executes shared fixtures before timing.
6. **Benchmark matrix** executes controlled variants and datasets.
7. **Evidence panel** links raw output, assembly/profiles/counters and environment metadata.
8. **Analysis** separates observation, interpretation, limitations and non-portable conclusions.
9. **Exercises** require diagnosis, modification and evidence-based explanation.

## Experiment Matrix

### Variants

- cold invocation
- tiered warm-up
- steady-state compiled
- forced deoptimization
- Rust debug vs release as anti-example

### Datasets and Profiles

- small polymorphic call chain
- numeric kernel
- branch profile shift

### Metrics

- latency over time
- compilation count
- code size
- deoptimization events
- steady-state ns/op

### Evidence Tools

- JMH
- JFR
- JITWatch
- PrintCompilation/LogCompilation
- perfasm
- Criterion
- cargo asm

The implementation must capability-detect optional tools. Missing `perf c2c`, affinity, NUMA or architecture-specific assembly support must mark evidence as unavailable rather than fail the ordinary content build.

## Java Track

- Use the repository Java toolchain; do not silently benchmark an IDE/debug configuration.
- Use JMH for microbenchmarks and a dedicated harness for cross-thread, process, network or coordinated-load experiments where JMH is not the right abstraction.
- Keep setup outside the timed region unless setup cost is the experiment.
- Consume results and use runtime-generated parameters to prevent dead-code elimination and constant folding.
- Capture allocations and JVM flags where relevant.
- Explain JIT warm-up, compilation and deoptimization when they affect the result.
- Primary focus: Bytecode, interpreter, C1/C2 tiers, compilation logs, code cache and deoptimization.

## Rust Track

- Use the pinned release benchmark profile; debug builds are invalid for comparison.
- Use Criterion for microbenchmarks and an explicit harness for cross-thread/process/network experiments.
- Use `black_box` and separate setup from timing.
- Keep unsafe code isolated, documented and covered by correctness/fuzz/model checks where relevant.
- Do not weaken validation or ownership guarantees solely to beat Java.
- Primary focus: AOT compilation as the contrast: optimization levels, monomorphization and code generation before execution.

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

- timing Java before warm-up and Rust after optimization
- treating one warm-up threshold as universal
- forcing compilation flags without explaining them
- claiming JIT always wins or loses

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
