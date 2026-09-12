## ADDED Requirements

### Requirement: Cache coherence mechanism explained
The mesi lab MUST explain cache coherence as a mechanism ensuring a coherent view of a cache line across participating cores, distinct from memory consistency and memory ordering.

#### Scenario: Distinguishes consistency
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly distinguishes cache coherence (per-location agreement) from memory consistency (cross-location ordering guarantees)

#### Scenario: Distinguishes memory ordering
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly distinguishes coherence from memory ordering (fences, `volatile`, `Acquire`/`Release`) as the mechanism used to constrain a consistency model

#### Scenario: Ownership, sharing, invalidation, write-back explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains ownership, sharing, invalidation, and write-back in terms a reader can map onto the interactive model's transitions

#### Scenario: No universal protocol claim
- **WHEN** the lab describes MESI
- **THEN** it avoids claiming MESI is the coherence protocol every processor implements

### Requirement: Interactive MESI state transitions
The mesi lab's interactive model MUST expose explicit four-state (Modified/Exclusive/Shared/Invalid) transitions for two simulated CPUs sharing one conceptual memory line.

#### Scenario: Both CPUs can read, write, and evict
- **WHEN** a user activates a CPU's read, write, or evict control
- **THEN** the corresponding MESI transition is applied and rendered, including ownership transfer where applicable

#### Scenario: State visible in text
- **WHEN** the interactive model's state changes
- **THEN** each CPU's MESI state and cached value are shown as text, not colour alone

#### Scenario: Invalidations and write-backs counted
- **WHEN** a transition invalidates another CPU's copy or writes a dirty value back to memory
- **THEN** the corresponding counter in the state inspector increments

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state (both lines Invalid, zero counters) for the current scenario

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, read/write/evict buttons, step controls) is reachable and operable by keyboard

### Requirement: Protocol caveats
The mesi lab MUST explain that MESI is a teaching model, not a universal implementation contract.

#### Scenario: Mentions protocol variants
- **WHEN** a user reads the "caveats" section
- **THEN** it mentions MESIF, MOESI, and directory-based coherence as real-world variants beyond the four-state model shown

#### Scenario: Avoids claiming a specific chip's protocol
- **WHEN** the lab discusses ARM or Apple Silicon
- **THEN** it states that the exact coherence protocol is unpublished/implementation-specific rather than asserting the displayed model matches it

#### Scenario: Labels the visualisation conceptual
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating the model is conceptual, not a cycle-accurate simulation of any specific processor

### Requirement: Java and Rust coherence examples
The mesi lab MUST include buildable examples showing a shared-writer pattern and a single-writer ownership pattern in both languages.

#### Scenario: Java example compiles
- **WHEN** the Java example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Rust example compiles
- **WHEN** the Rust example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Memory-ordering choices documented
- **WHEN** the Rust example is inspected
- **THEN** it documents why `Ordering::Relaxed` is sufficient for the shared-writer counter

#### Scenario: No direct coherence-state control claimed
- **WHEN** either language example is inspected
- **THEN** it states that the example cannot directly observe or force a MESI state from source code

### Requirement: Coherence diagnostic methodology
The mesi lab MUST explain how coherence traffic can be investigated on supported Linux systems, without presenting a synthetic benchmark as universal.

#### Scenario: perf c2c methodology included
- **WHEN** a user reads the "Diagnostic methodology" section
- **THEN** it explains how `perf c2c` identifies cross-core cache-to-cache (HITM) traffic and gives example commands

#### Scenario: Hardware and OS caveats included
- **WHEN** a user reads the "Diagnostic methodology" section
- **THEN** it states that `perf c2c` is Linux-specific, requires PMU access, and that event availability varies by microarchitecture and kernel version

#### Scenario: No universal benchmark claim
- **WHEN** a user reads the "Diagnostic methodology" section
- **THEN** it explicitly avoids presenting a synthetic "MESI benchmark" as a portable performance number

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and observe coherence traffic using the provided code and `perf c2c`

### Requirement: MESI educational completion
The mesi lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: When coherence reasoning matters
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which reasoning about coherence traffic has high payoff

#### Scenario: When it is not the bottleneck
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which coherence is unlikely to be the dominant cost

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering hardware and language-level claims
