## ADDED Requirements

### Requirement: Cache hierarchy mechanism explained
The cache-hierarchy lab MUST explain the L1/L2/L3/RAM hierarchy, cache lines, spatial and temporal locality, and hardware prefetching as the mechanisms that determine whether an access is served by a fast cache level or by slow main memory.

#### Scenario: Locality distinguished
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly distinguishes spatial locality (closeness of addresses touched) from temporal locality (how soon a touched address is revisited), with an example of each

#### Scenario: Prefetching explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that sequential access lets hardware stride/stream prefetching hide RAM latency, and that random access has no detectable stride for a prefetcher to act on

#### Scenario: No universal cache size or line size claim
- **WHEN** the lab states a cache size, cache-line size, or latency figure
- **THEN** it labels the figure as illustrative/common, not a universal architectural guarantee, and directs readers to query their own hardware

### Requirement: Interactive working-set model
The lab's interactive model MUST let a user step through sequential and random memory access over working sets that fit in L1 and working sets that exceed the simulated cache hierarchy, and observe which cache level served each access as text.

#### Scenario: Four scenarios selectable
- **WHEN** a user opens the interactive model
- **THEN** four scenarios are selectable: sequential access fitting in L1, sequential access exceeding cache, random access fitting in L1, and random access exceeding cache

#### Scenario: Step-by-step access with result shown as text
- **WHEN** a user activates the Next control
- **THEN** the next access in the scenario's pattern is applied, and the resulting cache level (L1/L2/L3 hit or RAM miss) is shown as text, not colour alone

#### Scenario: Hit/miss counts visible
- **WHEN** a user steps through the model
- **THEN** the state inspector shows running L1/L2/L3 hit counts and RAM miss counts for the current run

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state (zero accesses, empty cache) for the current scenario

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, step controls) is reachable and operable by keyboard

### Requirement: Java example
The lab MUST provide a JMH-based Java example demonstrating sequential vs. random memory-access latency over a working set that fits in L1 and one that exceeds the last-level cache.

#### Scenario: Uses JMH
- **WHEN** the Java example is inspected
- **THEN** it is a JMH benchmark reporting average time per batch of pointer-chase accesses

#### Scenario: Defeats the optimizer
- **WHEN** the Java example is inspected
- **THEN** it uses a dependent pointer-chase access chain (not a plain independent-index loop) so the JIT cannot reorder, vectorize, or eliminate the memory-access pattern being measured

#### Scenario: Small and large working sets
- **WHEN** the Java example is inspected
- **THEN** it includes both a working set sized to fit comfortably in L1 and one sized to exceed any consumer last-level cache

### Requirement: Rust example
The lab MUST provide a Criterion-based Rust example demonstrating the same sequential-vs-random, small-vs-large comparison as the Java example.

#### Scenario: Uses Criterion
- **WHEN** the Rust example is benchmarked
- **THEN** it uses the Criterion benchmarking crate

#### Scenario: Defeats the optimizer
- **WHEN** the Rust example is inspected
- **THEN** it uses `black_box` around the pointer-chase table and result to prevent the compiler from const-folding or eliminating the access chain

#### Scenario: Single-cycle random permutation documented
- **WHEN** the Rust example builds its random access order
- **THEN** it documents why a single-cycle permutation (Sattolo's algorithm) is used instead of a plain shuffle, which can produce short sub-cycles

### Requirement: Honest results
Benchmark data MUST be clearly disclosed and simulated data MUST be clearly labelled.

#### Scenario: Simulated model labelled
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating it is a simplified model with illustrative cache capacities, not a cycle-accurate simulation

#### Scenario: Measured results include environment
- **WHEN** benchmark results are shown
- **THEN** they carry a `.disclosure.measured` block naming the tool, hardware, OS, runtime, warm-up and measurement configuration

#### Scenario: Charts include units and uncertainty or distribution
- **WHEN** the benchmark tables are shown
- **THEN** they state time units and a confidence interval or min/max spread across repeated runs, not a single bare number

### Requirement: Educational completion
The lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: When access pattern matters
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which optimizing memory-access pattern has high payoff

#### Scenario: When access pattern matters less
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which optimizing memory-access pattern has low payoff

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and measure the effect using the provided code, including adapting it to the reader's own cache sizes

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering hardware, JVM, and Rust claims
