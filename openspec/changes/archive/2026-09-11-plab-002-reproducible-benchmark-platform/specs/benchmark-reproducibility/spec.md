# Delta for benchmark-reproducibility

## ADDED Requirements

### Requirement: Pinned toolchains
Benchmark runs SHALL resolve through repository-pinned toolchains or record the exact external versions used.

#### Scenario: Pinned toolchains is enforced
- GIVEN A benchmark run starts
- WHEN the relevant content or result is built or displayed
- THEN the Java, JVM, Rust, compiler and harness versions are recorded
### Requirement: Host metadata
Every persisted run SHALL include CPU model, architecture, core topology, memory, OS, kernel and power-management metadata available without unsafe privilege escalation.

#### Scenario: Host metadata is enforced
- GIVEN A run is stored
- WHEN the relevant content or result is built or displayed
- THEN the host metadata accompanies the result
### Requirement: Run profiles
The platform SHALL provide smoke, development, full and publication profiles with explicit iteration and duration differences.

#### Scenario: Run profiles is enforced
- GIVEN A contributor chooses a profile
- WHEN the relevant content or result is built or displayed
- THEN the profile purpose and rigor are visible
### Requirement: Correctness before timing
Benchmark implementations SHALL pass shared correctness fixtures before performance results are accepted.

#### Scenario: Correctness before timing is enforced
- GIVEN A Java or Rust implementation changes
- WHEN the relevant content or result is built or displayed
- THEN semantic fixture checks run before publication benchmarks
### Requirement: Comparable configuration
Cross-language comparison SHALL reject or visibly flag debug builds, unequal datasets, unequal semantics or missing warm-up/configuration metadata.

#### Scenario: Comparable configuration is enforced
- GIVEN An incomparable run is imported
- WHEN the relevant content or result is built or displayed
- THEN the result is marked invalid or non-comparable
### Requirement: Raw result preservation
The platform SHALL preserve unrounded harness output and environment metadata as immutable run artifacts.

#### Scenario: Raw result preservation is enforced
- GIVEN A result is published
- WHEN the relevant content or result is built or displayed
- THEN the original files remain available
