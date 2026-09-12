## ADDED Requirements

### Requirement: Thread-per-core mechanism explained
The thread-per-core lab MUST explain thread-per-core as an ownership and execution model, not merely one thread for every logical CPU.

#### Scenario: Ownership discipline distinguished from thread count
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that thread-per-core means each thread exclusively owns a partition of state for its lifetime, and that spawning one thread per core while still sharing state through a lock does not qualify

#### Scenario: Reuses SPSC ring buffer for handoff
- **WHEN** a user reads the "Cross-core handoff" theory section
- **THEN** it explains that handoff between cores is implemented as one bounded SPSC channel per ordered core pair, per the SPSC Ring Buffer lab's reservation/publication/read/acknowledgement discipline

### Requirement: Architecture comparison
The lab MUST compare a shared worker pool against thread-per-core owned-state execution.

#### Scenario: Shared pool contention explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that a shared worker pool's requests all serialize on one lock regardless of worker count, contrasted with owned-state execution's lack of any lock

#### Scenario: Interactive comparison shows the turn-count difference
- **WHEN** a user compares the "Shared worker pool" and "Thread-per-core ownership" scenarios for the same four requests
- **THEN** the state inspector shows the shared-pool scenario taking more turns (steps) to fully process the same requests than the owned-state scenario

### Requirement: Handoff and backpressure
The lab MUST show bounded queues, handoff cost, and overload behaviour.

#### Scenario: Handoff costs an extra turn
- **WHEN** a user steps through the "Cross-core handoff" scenario to completion
- **THEN** it takes more turns to fully process the same number of requests than the "Thread-per-core ownership" scenario, and the event log attributes the difference to handoff latency

#### Scenario: Bounded queue rejects rather than grows unboundedly
- **WHEN** a user steps through the "Backpressure" scenario
- **THEN** requests that arrive after a core's inbox reaches capacity are rejected and counted, rather than the queue growing past its bound

#### Scenario: Hot partition overloads one core while others idle
- **WHEN** a user steps through the "Hot partition" scenario
- **THEN** the targeted core's processed and rejected counts both rise while every other core's processed count stays at zero

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, step controls, code-language tabs) is reachable and operable by keyboard

### Requirement: Affinity caveats
The lab MUST explain that affinity support and scheduler control differ across Linux, macOS, Windows, and container environments.

#### Scenario: Per-OS affinity differences stated
- **WHEN** a user reads the "Affinity and scheduler caveats" section
- **THEN** it states that Linux exposes fairly direct affinity control, macOS treats affinity only as a hint the scheduler may ignore, and Windows/containers each add their own caveats

#### Scenario: Migration distinguished from correctness
- **WHEN** a user steps through the "Scheduler migration" scenario
- **THEN** requests routed to the migrated core are still processed correctly, and the theory section explains that migration is a locality/performance concern, not a correctness concern

#### Scenario: NUMA caveat stated
- **WHEN** a user reads the "NUMA caveats" section
- **THEN** it states that thread-per-core's benefit depends on NUMA-aware memory placement in addition to CPU affinity, and that the interactive model does not simulate NUMA distance

### Requirement: Thread-per-core Java and Rust examples
The lab MUST include buildable examples using bounded SPSC communication concepts, contrasting shared-lock state with owned-partition state.

#### Scenario: Buildable examples
- **WHEN** the Java and Rust example projects are built
- **THEN** each compiles and its tests pass

#### Scenario: Shared-lock and owned-partition variants both present
- **WHEN** the Java and Rust examples are inspected
- **THEN** each includes both a shared, lock-guarded counter pool and a per-partition owned counter with no synchronization

#### Scenario: JMH and Criterion benchmarks disclosed
- **WHEN** a user reads the benchmark methodology section
- **THEN** it discloses hardware, OS, runtime/toolchain versions, and measurement configuration for both languages, per the shared `.disclosure.measured` component

### Requirement: Thread-per-core trade-offs
The lab MUST explain hot partitions, underutilised cores, operational complexity, and workloads where the model is inappropriate.

#### Scenario: When to use stated
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which thread-per-core is worth adopting

#### Scenario: When not to use stated
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which thread-per-core is not appropriate, including cross-partition operations and unsuitable deployment environments

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and measure the effect using the provided code

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering the thread-per-core pattern, JVM, and Rust claims
