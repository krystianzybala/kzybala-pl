# Delta for performance-lab-curriculum

## ADDED Requirements

### Requirement: Curriculum manifest
The site SHALL expose a machine-readable and human-readable manifest of all planned laboratories, their category, level, status, prerequisites and evidence maturity.

#### Scenario: Curriculum manifest is enforced
- GIVEN A visitor opens the laboratory index
- WHEN the relevant content or result is built or displayed
- THEN the visitor can distinguish implemented, partial, planned and verified laboratories without inferred status
### Requirement: Reference-lab preservation
Existing False Sharing, Ring Buffer and JIT Pipeline content SHALL be inventoried and preserved until replacement content is verified.

#### Scenario: Reference-lab preservation is enforced
- GIVEN A reference lab is migrated
- WHEN the relevant content or result is built or displayed
- THEN existing routes and valuable content remain accessible or have an explicit redirect and migration record
### Requirement: Standard learning sequence
Every laboratory SHALL follow the sequence theory, visualization, Java, Rust, benchmark, evidence and exercises unless a documented exception is approved.

#### Scenario: Standard learning sequence is enforced
- GIVEN A laboratory is published
- WHEN the relevant content or result is built or displayed
- THEN all mandatory sequence sections are present or the exception is visible
### Requirement: Evidence maturity
Every result SHALL carry an evidence maturity of draft, reproduced, profiled or verified.

#### Scenario: Evidence maturity is enforced
- GIVEN A benchmark result is displayed
- WHEN the relevant content or result is built or displayed
- THEN the maturity and missing verification steps are visible
### Requirement: Neutral comparison
Cross-language conclusions SHALL explain semantic equivalence, toolchain settings and uncertainty rather than ranking languages from a single number.

#### Scenario: Neutral comparison is enforced
- GIVEN Java and Rust results are compared
- WHEN the relevant content or result is built or displayed
- THEN the page states what is equivalent, what differs and what cannot be concluded
### Requirement: Progressive learning paths
The curriculum SHALL offer at least foundational, intermediate, advanced and capstone paths with prerequisites.

#### Scenario: Progressive learning paths is enforced
- GIVEN A visitor selects a learning path
- WHEN the relevant content or result is built or displayed
- THEN the next recommended labs are ordered and prerequisites are visible
### Requirement: No synthetic completion
Planned laboratories SHALL not appear complete until executable code, raw results and evidence are present.

#### Scenario: No synthetic completion is enforced
- GIVEN A lab lacks measured evidence
- WHEN the relevant content or result is built or displayed
- THEN its status remains planned or partial
