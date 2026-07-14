# Proposal: Reproducible Benchmark Platform

## Intent

Make benchmark results reproducible enough to teach performance engineering rather than merely decorate portfolio pages.

## Why Now

The Performance Lab already contains valuable experiments, but without a shared curriculum, evidence contract and implementation order it can drift into isolated demos. This change creates the contract that later laboratory changes must obey.

## Scope

- Define pinned Java, Rust, JMH and Criterion toolchain resolution.
- Capture host CPU, topology, OS, kernel, governor, frequency, memory and runtime flags.
- Create canonical benchmark commands and profiles for micro, concurrent and macro labs.
- Add correctness checks before timing and smoke/full/release run modes.
- Detect uncontrolled or incomparable environments and label results accordingly.

## Non-Goals

- Guarantee identical nanoseconds on different hardware.
- Require privileged Linux tuning for the default developer run.
- Normalize away meaningful Java and Rust runtime differences.

## Dependencies

- Inspect and preserve repository reality before changing routes, frameworks or build tools.
- Coordinate with the other foundation changes in this package.
- Do not mark later laboratories complete merely because their OpenSpec exists.

## Completion Evidence

- The behavior requirements in the delta spec are implemented and tested.
- Existing Performance Lab content is preserved or migrated with explicit parity evidence.
- No benchmark number is fabricated, manually rounded into source, or presented without provenance.
- `openspec validate plab-002-reproducible-benchmark-platform --strict` succeeds on a compatible OpenSpec CLI.
