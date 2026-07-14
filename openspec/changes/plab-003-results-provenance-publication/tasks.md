# Tasks

- [ ] 1. Inventory existing benchmark result files and manually embedded numbers.
- [ ] 2. Define the versioned canonical result schema.
- [ ] 3. Implement JMH and Criterion importers.
- [ ] 4. Implement histogram and perf-counter import support.
- [ ] 5. Implement provenance and comparability validation.
- [ ] 6. Implement deterministic chart/table data generation.
- [ ] 7. Add evidence-maturity workflow and validation.
- [ ] 8. Add compatible historical-run comparison and regression thresholds.
- [ ] 9. Migrate existing reference-lab data without inventing missing metadata.
- [ ] 10. Add golden-file importer and renderer tests.
- [ ] 11. Add a content gate rejecting unprovenanced production charts.
- [ ] 12. Run OpenSpec validation and repository quality gates.

## Completion Gate

- [ ] All behavior requirements have automated or explicitly documented verification.
- [ ] Existing content has not been silently deleted.
- [ ] No fabricated benchmark data is present.
- [ ] `openspec validate plab-003-results-provenance-publication --strict` passes.

<!--
2026-07-14 remediation note (do not remove until archival): an
archival-readiness audit found that every task above had been checked
despite critical gaps in the enforced code path (non-finite numbers passing
schema validation, no cryptographic hash covering measured values,
legacy-unprovenanced reachable to "verified" through the actual gate, raw
artifact path traversal, importer field loss, decorative evidence-maturity
state machine, etc — see the audit transcript for the full finding list).
Checkboxes above were reset to false and must only be re-checked once their
acceptance criteria are demonstrated by passing adversarial tests, not
merely by the existence of files or golden-path tests.
-->
