## ADDED Requirements

### Requirement: SPSC ownership model explained
The spsc-ring-buffer lab MUST explain a bounded single-producer/single-consumer ring buffer as an ownership-discipline problem, distinct from the contended CAS retry loops in the CAS Contention lab.

#### Scenario: Single-writer cursors explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that exactly one thread ever writes the head cursor and exactly one thread ever writes the tail cursor, so neither cursor is ever contended

#### Scenario: Contrasted with CAS contention
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly contrasts the SPSC buffer's zero-contention cursors with the CAS Contention lab's contended retry loop, as the "single-writer alternative" applied structurally

### Requirement: Five-phase separation
The spsc-ring-buffer lab MUST explicitly separate reservation, payload write, publication, payload read, and consumption acknowledgement as distinct phases.

#### Scenario: Producer phases distinguished
- **WHEN** a user reads the lab's theory section
- **THEN** it describes reservation, payload write, and publication as three separate producer-side phases, in that order

#### Scenario: Consumer phases distinguished
- **WHEN** a user reads the lab's theory section
- **THEN** it describes payload read and consumption acknowledgement as two separate consumer-side phases, in that order

### Requirement: Interactive ring-buffer model
The lab's interactive model MUST let a user step through normal flow, wrap-around, full-buffer, and empty-buffer scenarios and observe cursor state as text.

#### Scenario: Eight scenarios selectable
- **WHEN** a user opens the interactive model
- **THEN** eight scenarios are selectable: normal flow, wrap-around, full buffer, empty buffer, cached cursor, batch publication, publish-before-write bug, and overwrite-unconsumed bug

#### Scenario: Wrap-around demonstrated
- **WHEN** a user steps through the "Wrap-around" scenario to its end
- **THEN** a later produce reuses a slot index that an earlier, already-acknowledged produce also used, and the state inspector shows this as normal behaviour rather than an error

#### Scenario: Full and empty backpressure demonstrated
- **WHEN** a user steps through the "Full buffer" or "Empty buffer" scenario
- **THEN** the rejected-reservation or starved-read counter increments respectively, and the event log states the buffer was full or empty rather than a crash

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, step controls, code-language tabs) is reachable and operable by keyboard

### Requirement: Cached-cursor optimisation
The lab MUST demonstrate a producer-side and consumer-side cached cursor that avoids reading the other side's real cursor on every operation.

#### Scenario: Cache hit vs refresh counted
- **WHEN** a user steps through any scenario
- **THEN** the state inspector shows separate producer and consumer cache-hit and cache-refresh counts

#### Scenario: Refresh recovers capacity
- **WHEN** a user steps through the "Cached cursor" scenario to its 5th reservation
- **THEN** the reservation's cached view pessimistically reports the buffer full, triggers a refresh of the real tail, and then succeeds once the refresh reveals room the consumer had already freed

### Requirement: Batch publication
The lab MUST demonstrate batching multiple reservations into a single publish and multiple reads into a single acknowledgement.

#### Scenario: Batch counters separate from single-item counters
- **WHEN** a user steps through the "Batch publication" scenario to its end
- **THEN** the state inspector shows exactly one batch publish and one batch acknowledgement, and zero single-item publishes or acknowledgements, for a 3-item batch

### Requirement: Correctness bugs demonstrated
The lab MUST demonstrate at least one incorrect publication-ordering bug and one overwrite bug, per spec.md's "Failure modes" requirement.

#### Scenario: Publish-before-write bug
- **WHEN** a user steps through the "Bug: publish before write" scenario
- **THEN** the consumer reads a slot's pre-existing stale value because the producer advanced the head cursor before writing the real payload, and the incorrect-read counter increments

#### Scenario: Overwrite-unconsumed bug
- **WHEN** a user steps through the "Bug: overwrite unconsumed" scenario
- **THEN** the producer overwrites a slot the consumer has not yet acknowledged because its reservation step skips the capacity check, and the overwrite counter increments

### Requirement: SPSC Java and Rust coverage
The lab MUST include buildable, zero-allocation-on-the-hot-path Java and Rust examples and comparable benchmark methodology.

#### Scenario: Buildable examples
- **WHEN** the Java and Rust example projects are built
- **THEN** each compiles and its tests pass

#### Scenario: Zero allocation on the hot path
- **WHEN** the Java and Rust example's produce/consume methods are inspected
- **THEN** neither allocates on a call, using primitive `long`/`u64` slots and a caller-provided output parameter (Java) or a plain return value (Rust) rather than boxing

#### Scenario: JMH and Criterion benchmarks disclosed
- **WHEN** a user reads the benchmark methodology section
- **THEN** it discloses hardware, OS, runtime/toolchain versions, and measurement configuration for both languages, per the shared `.disclosure.measured` component

### Requirement: Cross-language benchmark honesty
The lab MUST NOT present its Java and Rust benchmark numbers as directly comparable when their measurement methodologies differ.

#### Scenario: Methodology difference disclosed
- **WHEN** a user reads the benchmark methodology section
- **THEN** it explicitly states that the Java benchmark measures persistent-thread steady-state throughput while the Rust benchmark's timed region includes thread spawn/join overhead per sample, and that the two numbers must not be compared directly

### Requirement: SPSC educational completion
The spsc-ring-buffer lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps, including the two bugs demonstrated above

#### Scenario: When to use
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which an SPSC ring buffer is the right structure

#### Scenario: When not to use
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which an SPSC ring buffer is not the right structure, including multi-producer/multi-consumer workloads

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and measure the effect using the provided code, including reproducing the publish-before-write bug

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering the ring-buffer pattern, JVM, and Rust claims
