# Delta for performance-lab-framework

## ADDED Requirements

### Requirement: Reusable lab shell
Every laboratory SHALL render through a shared shell with consistent navigation, metadata and section order.

#### Scenario: Reusable lab shell is enforced
- GIVEN A visitor opens any lab
- WHEN the relevant content or result is built or displayed
- THEN the same navigation and section landmarks are available
### Requirement: Theory and hypothesis
Every lab SHALL state the performance question, hypothesis and falsifiable conditions before showing results.

#### Scenario: Theory and hypothesis is enforced
- GIVEN A visitor reaches the introduction
- WHEN the relevant content or result is built or displayed
- THEN the expected mechanism and possible disproof are visible
### Requirement: Java and Rust tracks
Every applicable lab SHALL provide runnable Java and Rust tracks with explicit semantic equivalence notes.

#### Scenario: Java and Rust tracks is enforced
- GIVEN Both implementations exist
- WHEN the relevant content or result is built or displayed
- THEN the page identifies shared inputs, outputs and guarantees
### Requirement: Benchmark configuration
Every lab SHALL display benchmark configuration, toolchain, host metadata and run commands.

#### Scenario: Benchmark configuration is enforced
- GIVEN A result is shown
- WHEN the relevant content or result is built or displayed
- THEN the visitor can reproduce the run configuration
### Requirement: Raw evidence
Every summary chart SHALL link to raw result data and profiler evidence where required.

#### Scenario: Raw evidence is enforced
- GIVEN A visitor inspects a chart
- WHEN the relevant content or result is built or displayed
- THEN the underlying data and evidence files are reachable
### Requirement: Exercise contract
Every lab SHALL provide at least one diagnosis exercise and one implementation exercise.

#### Scenario: Exercise contract is enforced
- GIVEN A visitor reaches exercises
- WHEN the relevant content or result is built or displayed
- THEN the exercises include success criteria and hints separated from solutions
### Requirement: Accessible visualization
Charts and animations SHALL have readable labels, keyboard-accessible controls where interactive and textual summaries.

#### Scenario: Accessible visualization is enforced
- GIVEN A user cannot perceive or operate the visual
- WHEN the relevant content or result is built or displayed
- THEN the core conclusion remains available in text/table form
