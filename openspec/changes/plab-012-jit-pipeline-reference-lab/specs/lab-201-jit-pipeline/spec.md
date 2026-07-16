# Delta for lab-201-jit-pipeline

## ADDED Requirements

### Requirement: Performance question and falsifiable hypothesis
The laboratory SHALL state the question “How does Java code move from bytecode through tiered compilation, and why do benchmark results change during warm-up?” and the hypothesis “Tiered compilation improves steady-state throughput but introduces phase changes, recompilation and deoptimization that must be visible in any honest Java/Rust comparison.” before presenting results.

#### Scenario: Performance question and falsifiable hypothesis is satisfied
- GIVEN the laboratory introduction is rendered
- WHEN the laboratory is built, executed or reviewed
- THEN the performance mechanism, expected result and conditions that could disprove the hypothesis are visible
### Requirement: Mechanism-first explanation
The laboratory SHALL explain the relevant jvm runtime mechanism before exposing optimized source code.

#### Scenario: Mechanism-first explanation is satisfied
- GIVEN a learner starts the laboratory
- WHEN the laboratory is built, executed or reviewed
- THEN the theory and mechanism visualization precede benchmark conclusions
### Requirement: Java implementation track
The laboratory SHALL provide runnable Java implementations focused on Bytecode, interpreter, C1/C2 tiers, compilation logs, code cache and deoptimization.

#### Scenario: Java implementation track is satisfied
- GIVEN the Java track is selected
- WHEN the laboratory is built, executed or reviewed
- THEN source, build command, run command and correctness result are available
### Requirement: Rust implementation track
The laboratory SHALL provide runnable Rust implementations focused on AOT compilation as the contrast: optimization levels, monomorphization and code generation before execution.

#### Scenario: Rust implementation track is satisfied
- GIVEN the Rust track is selected
- WHEN the laboratory is built, executed or reviewed
- THEN source, build command, run command and correctness result are available
### Requirement: Semantic equivalence
Compared Java and Rust variants SHALL use equivalent inputs, outputs and correctness guarantees, with intentional differences documented.

#### Scenario: Semantic equivalence is satisfied
- GIVEN a cross-language chart is displayed
- WHEN the laboratory is built, executed or reviewed
- THEN the equivalence contract and any non-equivalent behavior are visible
### Requirement: Controlled variant matrix
The laboratory SHALL include the controlled variants: cold invocation, tiered warm-up, steady-state compiled, forced deoptimization, Rust debug vs release as anti-example.

#### Scenario: Controlled variant matrix is satisfied
- GIVEN the benchmark matrix is built
- WHEN the laboratory is built, executed or reviewed
- THEN each required variant is either measured or explicitly marked unsupported with a reason
### Requirement: Dataset and parameter coverage
The laboratory SHALL exercise these data profiles where applicable: small polymorphic call chain, numeric kernel, branch profile shift.

#### Scenario: Dataset and parameter coverage is satisfied
- GIVEN full-profile benchmarks run
- WHEN the laboratory is built, executed or reviewed
- THEN the result artifacts identify the dataset and parameter values for every sample
### Requirement: Required measurements
The laboratory SHALL capture and correctly label: latency over time, compilation count, code size, deoptimization events, steady-state ns/op.

#### Scenario: Required measurements is satisfied
- GIVEN results are imported
- WHEN the laboratory is built, executed or reviewed
- THEN units, aggregation, sample count and uncertainty metadata are present
### Requirement: Profiler and counter evidence
The laboratory SHALL attach relevant evidence from the repository-supported subset of JMH, JFR, JITWatch, PrintCompilation/LogCompilation, perfasm, Criterion, cargo asm.

#### Scenario: Profiler and counter evidence is satisfied
- GIVEN a mechanism claim is made
- WHEN the laboratory is built, executed or reviewed
- THEN at least one evidence artifact supports or challenges the claim
### Requirement: Reproducible commands
The laboratory SHALL show exact correctness, smoke, full and profiler commands resolved from repository scripts.

#### Scenario: Reproducible commands is satisfied
- GIVEN a contributor opens reproduction instructions
- WHEN the laboratory is built, executed or reviewed
- THEN the commands and expected artifact locations are available
### Requirement: Environment provenance
Every persisted run SHALL include toolchain, source revision, CPU/architecture, OS/kernel and available topology/power metadata.

#### Scenario: Environment provenance is satisfied
- GIVEN a result set is displayed
- WHEN the laboratory is built, executed or reviewed
- THEN its provenance is accessible without consulting chat history
### Requirement: No fabricated results
The laboratory SHALL reject publication data that lacks raw artifacts or was manually entered as a measured value.

#### Scenario: No fabricated results is satisfied
- GIVEN the site build processes a result set
- WHEN the laboratory is built, executed or reviewed
- THEN unprovenanced measured values fail validation
### Requirement: Pitfall disclosure
The laboratory SHALL explicitly discuss these traps: timing Java before warm-up and Rust after optimization; treating one warm-up threshold as universal; forcing compilation flags without explaining them; claiming JIT always wins or loses.

#### Scenario: Pitfall disclosure is satisfied
- GIVEN a learner reviews conclusions
- WHEN the laboratory is built, executed or reviewed
- THEN the limitations and common invalid interpretations are visible
### Requirement: Educational visualization
The laboratory SHALL provide data-driven or deterministic visualizations covering: tiered compilation timeline, latency-by-iteration plot, deoptimization event overlay.

#### Scenario: Educational visualization is satisfied
- GIVEN a visualization is shown
- WHEN the laboratory is built, executed or reviewed
- THEN a textual/table fallback communicates the same core result
### Requirement: Exercises and verification
The laboratory SHALL include at least one diagnosis exercise, one code-change exercise and one evidence-interpretation exercise with success criteria.

#### Scenario: Exercises and verification is satisfied
- GIVEN a learner completes an exercise
- WHEN the laboratory is built, executed or reviewed
- THEN the expected verification method is available separately from the initial prompt
### Requirement: Conclusion integrity
The conclusion SHALL distinguish observed evidence, mechanism-based interpretation and unproven inference.

#### Scenario: Conclusion integrity is satisfied
- GIVEN the result set changes materially
- WHEN the laboratory is built, executed or reviewed
- THEN the conclusion is marked for re-verification before retaining verified status
### Requirement: Native-Linux publication evidence
Publication measurements SHALL be collected exclusively on the dedicated native-Linux benchmark host through the unified evidence runner and its per-lab configuration, and the laboratory SHALL render an awaiting-native-linux-measurement state until such evidence is imported and reviewed.

#### Scenario: Native-Linux publication evidence is satisfied
- GIVEN the laboratory displays a measured result or renders before evidence import
- WHEN the laboratory is built, executed or reviewed
- THEN every displayed measurement traces to imported native-Linux evidence and pre-import builds show the awaiting state instead of numbers
