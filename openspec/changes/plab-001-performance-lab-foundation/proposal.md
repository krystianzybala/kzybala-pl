# Proposal: Performance Lab Curriculum Foundation

## Intent

Turn the existing kzybala.pl Performance Lab into a coherent runtime-engineering curriculum rather than a collection of disconnected benchmark pages.

## Why Now

The Performance Lab already contains valuable experiments, but without a shared curriculum, evidence contract and implementation order it can drift into isolated demos. This change creates the contract that later laboratory changes must obey.

## Scope

- Define the curriculum map, levels, prerequisites and learning paths for 36 Java/Rust laboratories.
- Treat the existing False Sharing, Ring Buffer and JIT Pipeline pages as reference labs to inventory and harden.
- Define the mandatory flow: theory → visualization → Java → Rust → benchmark → evidence → exercises.
- Add progress, status and maturity metadata without inventing completed work.
- Create a stable lab manifest that later changes can extend.

## Non-Goals

- Implement all laboratory code in this change.
- Publish fabricated benchmark numbers.
- Declare Java or Rust the universal winner.
- Replace the current site stack merely to satisfy the specification.

## Dependencies

- Inspect and preserve repository reality before changing routes, frameworks or build tools.
- Coordinate with the other foundation changes in this package.
- Do not mark later laboratories complete merely because their OpenSpec exists.

## Completion Evidence

- The behavior requirements in the delta spec are implemented and tested.
- Existing Performance Lab content is preserved or migrated with explicit parity evidence.
- No benchmark number is fabricated, manually rounded into source, or presented without provenance.
- `openspec validate plab-001-performance-lab-foundation --strict` succeeds on a compatible OpenSpec CLI.
