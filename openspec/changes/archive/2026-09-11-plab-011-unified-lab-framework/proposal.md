# Proposal: Unified Laboratory Framework

## Intent

Create one reusable laboratory framework so every lab has the same navigable learning flow, data contract and verification surface.

## Why Now

The Performance Lab already contains valuable experiments, but without a shared curriculum, evidence contract and implementation order it can drift into isolated demos. This change creates the contract that later laboratory changes must obey.

## Scope

- Create reusable page sections for theory, visualization, Java, Rust, benchmark setup, results, profiler evidence and exercises.
- Define a lab content schema and validation rules.
- Support side-by-side and sequential comparison without forcing identical source structure.
- Provide accessible tables and charts with textual fallbacks.
- Add a per-lab verification checklist and maturity badge.

## Non-Goals

- Implement the domain content of all 36 labs.
- Add a browser-based benchmark runner that pretends client hardware is controlled.
- Tie the framework to a single chart library when the repository already has an approved one.

## Dependencies

- Inspect and preserve repository reality before changing routes, frameworks or build tools.
- Coordinate with the other foundation changes in this package.
- Do not mark later laboratories complete merely because their OpenSpec exists.

## Completion Evidence

- The behavior requirements in the delta spec are implemented and tested.
- Existing Performance Lab content is preserved or migrated with explicit parity evidence.
- No benchmark number is fabricated, manually rounded into source, or presented without provenance.
- `openspec validate plab-011-unified-lab-framework --strict` succeeds on a compatible OpenSpec CLI.
