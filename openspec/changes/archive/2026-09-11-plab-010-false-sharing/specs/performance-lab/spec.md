## ADDED Requirements

### Requirement: False sharing mechanism explained
The false-sharing lab MUST explain false sharing as coherence traffic caused by independent mutable data sharing one coherence unit, distinct from data races and lock contention.

#### Scenario: Distinguishes data races
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly explains how false sharing differs from a data race in both cause and fix

#### Scenario: Distinguishes lock contention
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly explains how false sharing differs from lock contention in both cause and observable symptom

#### Scenario: No universal cache-line claim
- **WHEN** the lab states a cache-line size
- **THEN** it labels 64 bytes as a common example, not a universal architectural guarantee

### Requirement: Interactive shared-line scenario
The lab's interactive model MUST let a user trigger writes/reads from either simulated CPU and observe the resulting coherence state as text.

#### Scenario: Both CPUs can write
- **WHEN** a user activates the CPU 0 write or CPU 1 write control
- **THEN** the corresponding cache-line transition is applied and rendered

#### Scenario: State visible in text
- **WHEN** the interactive model's state changes
- **THEN** each CPU's line state and last operation are shown as text, not colour alone

#### Scenario: Invalidation count visible
- **WHEN** a write causes an invalidation
- **THEN** the invalidation counter shown in the state inspector increments

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state for the current scenario

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, write/read buttons, step controls) is reachable and operable by keyboard

### Requirement: Padded comparison
The lab MUST let a user compare a shared-line layout against a padded layout and understand the trade-off, without claiming padding always helps.

#### Scenario: Shared and padded layouts selectable
- **WHEN** a user selects the "Padded line" scenario
- **THEN** the model switches to a layout where each CPU's counter has its own coherence unit

#### Scenario: Separate coherence units shown
- **WHEN** the padded scenario is active
- **THEN** writes from one CPU never invalidate the other CPU's line, and the state inspector reflects zero invalidations/transfers regardless of activity

#### Scenario: Memory-footprint trade-off explained
- **WHEN** a user reads the "When to use padding" section
- **THEN** it states the memory-footprint and cache-density cost of padding, not only its benefit

#### Scenario: No claim padding always helps
- **WHEN** a user reads the trade-offs section
- **THEN** it lists specific conditions under which padding does not help or actively hurts

### Requirement: Java example
The lab MUST provide a JMH-based Java example demonstrating shared, padded, and `@Contended` counter layouts.

#### Scenario: Uses JMH
- **WHEN** the Java example is inspected
- **THEN** it is a JMH benchmark using `@Group`/`Scope.Group` to force concurrent execution of the paired writes

#### Scenario: Shared and padded variants
- **WHEN** the Java example is inspected
- **THEN** it includes both a shared-layout counter class and a manually padded counter class

#### Scenario: Prevents elimination
- **WHEN** the Java example is inspected
- **THEN** it documents the risk of dead-field elimination for manual padding and recommends `@Contended` where JVM flags are controlled

#### Scenario: Documents `@Contended` flags
- **WHEN** the Java example uses `@Contended`
- **THEN** it documents the required `--add-exports` compiler/runtime flag and the `-XX:-RestrictContended` JVM flag

#### Scenario: Documents thread assignment
- **WHEN** the JMH benchmark is inspected
- **THEN** it documents how JMH assigns one thread per `@Benchmark` method within a `@Group`

### Requirement: Rust example
The lab MUST provide a Criterion-based Rust example demonstrating adjacent and cache-line-aligned atomic layouts.

#### Scenario: Uses atomics
- **WHEN** the Rust example is inspected
- **THEN** it uses `AtomicU64` counters incremented with `fetch_add`

#### Scenario: Adjacent and aligned variants
- **WHEN** the Rust example is inspected
- **THEN** it includes both a `#[repr(C)]` adjacent-atomics struct and a `#[repr(align(64))]` cache-line-aligned wrapper

#### Scenario: Uses Criterion or equivalent
- **WHEN** the Rust example is benchmarked
- **THEN** it uses the Criterion benchmarking crate with concurrent producer threads

#### Scenario: Documents ordering and alignment assumptions
- **WHEN** the Rust example is inspected
- **THEN** it documents why `Ordering::Relaxed` is sufficient and states the assumed 64-byte cache-line size explicitly as an assumption, not a guarantee

### Requirement: Honest results
Benchmark data MUST be clearly disclosed and simulated data MUST be clearly labelled.

#### Scenario: Simulated counters labelled
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating it is a simplified model, not a cycle-accurate simulation

#### Scenario: Measured results include environment
- **WHEN** benchmark results are shown
- **THEN** they carry a `.disclosure.measured` block naming the tool, hardware, OS, runtime, warm-up and measurement configuration

#### Scenario: Charts include units and uncertainty or distribution
- **WHEN** the benchmark table is shown
- **THEN** it states throughput units and a min–max spread across repeated runs, not a single bare number

### Requirement: Educational completion
The lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: When to use
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which padding is worth applying

#### Scenario: When not to use
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which padding is not worth applying

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and measure the effect using the provided code

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering hardware, JVM, and Rust claims
