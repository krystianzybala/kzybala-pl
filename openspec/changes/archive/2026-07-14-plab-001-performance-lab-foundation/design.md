# Design: Performance Lab Curriculum Foundation

## Context

This is a brownfield change. The current kzybala.pl Performance Lab, its routes, styling, Java/Rust repositories, benchmark assets and deployment mechanics must be inspected before implementation. The specification defines behavior and quality; it does not justify replacing working infrastructure for fashion.

## Goals

- Define the curriculum map, levels, prerequisites and learning paths for 36 Java/Rust laboratories.
- Treat the existing False Sharing, Ring Buffer and JIT Pipeline pages as reference labs to inventory and harden.
- Define the mandatory flow: theory → visualization → Java → Rust → benchmark → evidence → exercises.
- Add progress, status and maturity metadata without inventing completed work.
- Create a stable lab manifest that later changes can extend.

## Explicit Constraints

- Repository reality beats guessed filenames, frameworks and module names.
- Java and Rust examples must implement equivalent semantics before their numbers are compared.
- Raw benchmark output and environment metadata are primary evidence.
- Mean latency alone is not sufficient for latency-sensitive conclusions.
- Client-side browser benchmarks are educational demonstrations only unless the environment is controlled and clearly labeled.
- Existing False Sharing, Ring Buffer and JIT Pipeline work must be treated as migration inputs.

## Current-state inventory

Inspect the existing Performance Lab routes, content, components, benchmark assets and deployment flow before changing the structure. Repository reality wins over assumed frameworks.

## Curriculum model

Introduce a stable manifest with ids, slugs, categories, levels, prerequisites, implementation status, evidence maturity, supported architectures and last verified toolchains.

The 36 laboratories and the reference-tier labs (False Sharing, Ring Buffer, JIT Pipeline, plus the pre-existing cache/coherence/concurrency spine) are not disjoint sets — the manifest must model this as an explicit membership flag (`curriculumMember`) per entry, not infer it from category or from whether a follow-on change is still queued. See `docs/curriculum-manifest.md` ("Canonical terminology") for the resulting counts: 41 unique laboratories total, 8 reference-tier, 36 curriculum members, 3 of which overlap both.

## Migration strategy

Wrap or adapt the three existing labs first. Do not delete working content until the new shell reproduces its educational and benchmark value.

## Content contract

Use one semantic contract for Java and Rust examples. Any intentional semantic difference must be called out beside the benchmark.

## Editorial integrity

Disallow invented numbers, unlabeled synthetic charts, cherry-picked runs and claims based only on mean latency.

## Validation Strategy

1. Validate content/schema invariants at build time.
2. Run unit and integration tests for the changed platform behavior.
3. Run smoke benchmarks only to prove wiring.
4. Preserve publication-grade benchmark execution for controlled hosts.
5. Review all claims against raw evidence and comparability rules.

## Rollback

Keep migration changes additive until the new capability has parity. Existing routes and assets remain available until tests and manual review confirm replacement behavior.
