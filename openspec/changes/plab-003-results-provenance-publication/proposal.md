# Proposal: Results, Provenance and Publication Pipeline

## Intent

Create a trustworthy path from raw benchmark output to website charts, conclusions and regression history.

## Why Now

The Performance Lab already contains valuable experiments, but without a shared curriculum, evidence contract and implementation order it can drift into isolated demos. This change creates the contract that later laboratory changes must obey.

## Scope

- Define a versioned result schema for JMH, Criterion, histograms and perf counters.
- Import raw results without manual transcription.
- Store provenance, comparability, confidence and evidence maturity.
- Generate charts and tables deterministically.
- Support historical runs and regression thresholds without hiding variance.

## Non-Goals

- Create an online leaderboard.
- Automatically publish any faster local run.
- Reduce every experiment to one aggregate score.

## Dependencies

- Inspect and preserve repository reality before changing routes, frameworks or build tools.
- Coordinate with the other foundation changes in this package.
- Do not mark later laboratories complete merely because their OpenSpec exists.

## Completion Evidence

- The behavior requirements in the delta spec are implemented and tested.
- Existing Performance Lab content is preserved or migrated with explicit parity evidence.
- No benchmark number is fabricated, manually rounded into source, or presented without provenance.
- `openspec validate plab-003-results-provenance-publication --strict` succeeds on a compatible OpenSpec CLI.
