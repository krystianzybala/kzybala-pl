# Tasks

- [ ] 1. Inspect the current site framework, content model and chart components.
- [ ] 2. Define the lab content schema and build-time validator.
- [ ] 3. Implement the shared lab shell and section navigation.
- [ ] 4. Implement Java and Rust implementation-track components.
- [ ] 5. Implement benchmark configuration and environment metadata panels.
- [ ] 6. Implement result table/chart adapters with raw-data links.
- [ ] 7. Implement profiler evidence and pitfalls panels.
- [ ] 8. Implement exercise, hint and solution disclosure components.
- [ ] 9. Migrate one existing reference lab as a vertical slice.
- [ ] 10. Add accessibility, route and schema-validation tests.
- [ ] 11. Migrate the remaining existing reference labs without removing old content until parity is verified.
- [ ] 12. Run build, tests, content validation and OpenSpec strict validation.

## Completion Gate

- [ ] All behavior requirements have automated or explicitly documented verification.
- [ ] Existing content has not been silently deleted.
- [ ] No fabricated benchmark data is present.
- [ ] `openspec validate plab-011-unified-lab-framework --strict` passes.
