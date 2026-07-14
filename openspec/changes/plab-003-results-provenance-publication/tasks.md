# Tasks

- [x] 1. Inventory existing benchmark result files and manually embedded numbers.
- [x] 2. Define the versioned canonical result schema.
- [x] 3. Implement JMH and Criterion importers.
- [x] 4. Implement histogram and perf-counter import support.
- [x] 5. Implement provenance and comparability validation.
- [x] 6. Implement deterministic chart/table data generation.
- [x] 7. Add evidence-maturity workflow and validation.
- [x] 8. Add compatible historical-run comparison and regression thresholds.
- [x] 9. Migrate existing reference-lab data without inventing missing metadata.
- [x] 10. Add golden-file importer and renderer tests.
- [x] 11. Add a content gate rejecting unprovenanced production charts.
- [x] 12. Run OpenSpec validation and repository quality gates.

## Completion Gate

- [x] All behavior requirements have automated or explicitly documented verification.
- [x] Existing content has not been silently deleted.
- [x] No fabricated benchmark data is present.
- [x] `openspec validate plab-003-results-provenance-publication --strict` passes.

<!--
2026-07-14 remediation note (do not remove until archival): an
archival-readiness audit found that every task above had been checked
despite critical gaps in the enforced code path (non-finite numbers passing
schema validation, no cryptographic hash covering measured values,
legacy-unprovenanced reachable to "verified" through the actual gate, raw
artifact path traversal, importer field loss, decorative evidence-maturity
state machine, etc). Checkboxes were reset to false, then only re-checked
after a full remediation pass whose acceptance criteria are now proven by
110 automated tests (schema.js, numeric.js, provenance.js path-containment
and hash-mismatch detection, evidence-maturity.js's independent-dimensions
derivation, all four importers, regression.js, legacy-data.js), including
every adversarial case the audit found (NaN/Infinity rejection, unsafe
integer magnitudes, path traversal, symlink escape, capability-ceiling
enforcement, legacy-source-attempting-verified, missing
correctness/reviewer/profiling blocking verified, noise-floor-aware
regression classification). `npm run verify`, the full test suite, and
`openspec validate --strict` were run twice with identical canonical-artifact
hashes and no working-tree drift between runs. See the remediation
completion report for the full before/after finding list — several
non-critical gaps remain documented as open findings and do not block this
gate, but no fabricated or unenforced guarantee is claimed as met above.
-->
