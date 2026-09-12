# Tasks

- [x] 1. Inspect the current site framework, content model and chart components.
- [x] 2. Define the lab content schema and build-time validator.
- [x] 3. Implement the shared lab shell and section navigation.
- [x] 4. Implement Java and Rust implementation-track components.
- [x] 5. Implement benchmark configuration and environment metadata panels.
- [x] 6. Implement result table/chart adapters with raw-data links.
- [x] 7. Implement profiler evidence and pitfalls panels.
- [x] 8. Implement exercise, hint and solution disclosure components.
- [x] 9. Migrate one existing reference lab as a vertical slice.
- [x] 10. Add accessibility, route and schema-validation tests.
- [x] 11. Migrate the remaining existing reference labs without removing old content until parity is verified.
- [x] 12. Run build, tests, content validation and OpenSpec strict validation.

## Completion Gate

- [x] All behavior requirements have automated or explicitly documented verification.
- [x] Existing content has not been silently deleted.
- [x] No fabricated benchmark data is present.
- [x] `openspec validate plab-011-unified-lab-framework --strict` passes.
