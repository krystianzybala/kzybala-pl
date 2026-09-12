## ADDED Requirements

### Requirement: CAS mechanism explained
The cas-contention lab MUST explain compare-and-set as a conditional atomic update, distinct from a plain store.

#### Scenario: CAS semantics explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that CAS atomically replaces a value only if it matches an expected value, and reports success or failure

#### Scenario: Retry loop explained
- **WHEN** a user reads the lab's theory section
- **THEN** it shows the standard read-compute-CAS-or-retry loop shape that every CAS-based algorithm is built around

### Requirement: Contention model
The cas-contention lab's interactive model MUST show how retry loops amplify coherence traffic and latency under contention.

#### Scenario: Contention collapse demonstrated
- **WHEN** a user compares the "Two contenders" and "Many contenders" scenarios
- **THEN** the state inspector shows a higher failure/retry rate for more contenders, and the theory section names this "contention collapse"

#### Scenario: Ownership transfers counted
- **WHEN** a successful CAS changes which contender most recently succeeded
- **THEN** an ownership-transfer counter increments, tying the effect to cache-line ping-pong

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, step controls) is reachable and operable by keyboard

### Requirement: Backoff comparison
The cas-contention lab MUST compare no backoff, fixed backoff, exponential backoff, and jitter.

#### Scenario: Fixed and exponential backoff both reduce wasted retries
- **WHEN** a user compares "Fixed backoff" and "Exponential backoff" against "Many contenders" (no backoff)
- **THEN** both backoff scenarios show fewer failed CAS attempts than the no-backoff baseline at the same contender count

#### Scenario: Jitter explained
- **WHEN** a user reads the backoff section
- **THEN** it explains jitter's purpose (preventing contenders who failed together from retrying in lockstep again) using a reproducible, non-random mechanism

### Requirement: Single-writer alternative
The cas-contention lab MUST compare CAS contention with a single-writer ownership model.

#### Scenario: Single-writer scenario has zero contention
- **WHEN** a user selects the "Single-writer comparison" scenario
- **THEN** every attempt succeeds immediately, with zero failures and zero ownership transfers

#### Scenario: Not presented as universally better
- **WHEN** a user reads the single-writer section
- **THEN** it states the trade-off a single-writer design makes, not just its throughput advantage

### Requirement: CAS Java and Rust coverage
The cas-contention lab MUST include buildable Java and Rust examples and comparable benchmark methodology.

#### Scenario: Buildable examples
- **WHEN** the Java and Rust example projects are built
- **THEN** each compiles and its tests pass

#### Scenario: JMH and Criterion benchmarks disclosed
- **WHEN** a user reads the benchmark methodology section
- **THEN** it discloses hardware, OS, runtime/toolchain versions, and measurement configuration for both languages, per the shared `.disclosure.measured` component

#### Scenario: Contention collapse shown as measured data
- **WHEN** a user reads the benchmark table
- **THEN** it shows throughput at multiple contender counts in both languages, with the trend (not a cross-language absolute comparison) as the stated takeaway

### Requirement: CAS caveats
The cas-contention lab MUST explain fairness, starvation risk, ABA at a conceptual level, and workload sensitivity.

#### Scenario: ABA explained conceptually
- **WHEN** a user reads the ABA section
- **THEN** it explains that a successful CAS proves the value currently matches, not that nothing changed in between, without presenting a complete ABA solution

#### Scenario: Fairness and starvation risk stated
- **WHEN** a user reads the fairness section
- **THEN** it states that a CAS retry loop makes no fairness promise and starvation is possible in principle

#### Scenario: No universal backoff claim
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which backoff does not help or actively hurts
