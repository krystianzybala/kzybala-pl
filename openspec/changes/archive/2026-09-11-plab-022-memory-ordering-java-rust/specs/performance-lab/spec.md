## ADDED Requirements

### Requirement: Memory-ordering mechanism explained
The memory-ordering lab MUST explain why source order does not guarantee cross-thread observation order, distinguishing visibility, atomicity, and ordering as separate guarantees.

#### Scenario: Program order vs. observed order
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that compiler and CPU reordering can legally separate program order from another thread's observed order

#### Scenario: Visibility, atomicity, ordering distinguished
- **WHEN** a user reads the lab's theory section
- **THEN** it explains visibility, atomicity, and ordering as three independent guarantees, with an example operation for each combination

#### Scenario: happens-before and data races explained
- **WHEN** a user reads the lab's theory section
- **THEN** it defines happens-before and defines a data race in terms of it

### Requirement: Interactive litmus-test model
The memory-ordering lab's interactive model MUST expose deterministic, steppable scenarios for message-passing publication and store buffering.

#### Scenario: Broken and fixed publication both steppable
- **WHEN** a user steps through the "Broken publication" and "Release/acquire message passing" scenarios
- **THEN** each thread's buffered writes, the shared memory values, and the observed reads are shown as text at every step

#### Scenario: Store-buffering outcome togglable by ordering
- **WHEN** a user toggles the ordering selector between Relaxed and SeqCst on the "Store buffering" scenario
- **THEN** the scenario re-derives and shows the resulting outcome for the selected ordering, including whether the classic both-see-0 result occurred

#### Scenario: happens-before edges shown
- **WHEN** an acquire (or SeqCst) read observes a value published by a release (or SeqCst) write
- **THEN** a synchronizes-with / happens-before edge is added to a visible happens-before list

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state for the current scenario and ordering

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, ordering toggle, step controls) is reachable and operable by keyboard

### Requirement: Java VarHandle coverage
The memory-ordering lab MUST cover plain, opaque, acquire/release, and volatile VarHandle access modes with buildable examples.

#### Scenario: Buildable Java examples
- **WHEN** the Java example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Correct publication example
- **WHEN** the Java example is inspected
- **THEN** it includes both a plain-access publication example showing the broken outcome is possible and a release/acquire example that is reliably correct

#### Scenario: No claim that weaker modes are always faster
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions favoring weaker vs. stronger orderings, not a blanket performance claim

### Requirement: Rust atomic coverage
The memory-ordering lab MUST cover Relaxed, Acquire, Release, AcqRel, and SeqCst Rust atomic orderings with buildable examples.

#### Scenario: Buildable Rust examples
- **WHEN** the Rust example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Ordering choices documented
- **WHEN** the Rust example is inspected
- **THEN** it documents why each chosen ordering is sufficient for the operation it's applied to

#### Scenario: No undefined-behaviour example presented as valid
- **WHEN** the Rust example is inspected
- **THEN** every example given as correct is free of undefined behaviour under the documented ordering choice

### Requirement: Litmus scenario honesty
The memory-ordering lab MUST label its litmus scenarios as conceptual and avoid claiming to reproduce a specific CPU.

#### Scenario: Scenarios labelled conceptual
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating it fixes one illustrative interleaving per scenario, not a cycle-accurate simulation

#### Scenario: Allowed and forbidden outcomes explained
- **WHEN** a user reads the store-buffering scenario's description
- **THEN** it explains which outcome is allowed under Relaxed and which is forbidden under SeqCst

### Requirement: Memory-ordering myth correction
The memory-ordering lab MUST explicitly reject common myths about acquire/release, volatile, atomics, and SeqCst.

#### Scenario: Cache-flush myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states that acquire/release does not "flush the cache" and explains what it actually guarantees

#### Scenario: Volatile-instantaneous myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states that volatile provides ordering via happens-before, not global instantaneity

#### Scenario: Atomics-imply-correct-compound-algorithm myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states that a compound operation built from separate atomic steps is not itself atomic, with a concrete counter-example

#### Scenario: SeqCst-required-everywhere myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states concrete cases where a weaker ordering is sufficient and cheaper
