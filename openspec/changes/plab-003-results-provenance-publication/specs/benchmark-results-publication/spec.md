# Delta for benchmark-results-publication

## ADDED Requirements

### Requirement: Versioned result schema
All published measurements SHALL use a versioned schema that supports scalar benchmarks, latency histograms, counters and profiler references.

#### Scenario: Versioned result schema is enforced
- GIVEN A new result type is added
- WHEN the relevant content or result is built or displayed
- THEN the schema version and migration behavior are explicit
### Requirement: Provenance chain
Every displayed value SHALL trace to a raw artifact, source revision, command, environment and importer version.

#### Scenario: Provenance chain is enforced
- GIVEN A visitor or reviewer inspects a value
- WHEN the relevant content or result is built or displayed
- THEN the provenance chain can be followed
### Requirement: No manual numbers
Publication pages SHALL not accept hand-entered benchmark values outside explicitly labeled explanatory examples.

#### Scenario: No manual numbers is enforced
- GIVEN A chart is generated
- WHEN the relevant content or result is built or displayed
- THEN its values come from validated result artifacts
### Requirement: Uncertainty visibility
Results SHALL display dispersion or confidence information appropriate to the harness and shall not show only a best run.

#### Scenario: Uncertainty visibility is enforced
- GIVEN Multiple samples exist
- WHEN the relevant content or result is built or displayed
- THEN variance or confidence is visible
### Requirement: Regression history
The system SHALL retain historical verified runs and identify material regressions using lab-specific thresholds.

#### Scenario: Regression history is enforced
- GIVEN A new verified run is imported
- WHEN the relevant content or result is built or displayed
- THEN it is compared to compatible historical runs
### Requirement: Conclusion integrity
Lab conclusions SHALL be stored beside the result set and invalidated for review when underlying evidence changes materially.

#### Scenario: Conclusion integrity is enforced
- GIVEN A result is replaced or marked incomparable
- WHEN the relevant content or result is built or displayed
- THEN the associated conclusion requires re-verification
