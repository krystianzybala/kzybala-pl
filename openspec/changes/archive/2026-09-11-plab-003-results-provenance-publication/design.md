# Design: Results, Provenance and Publication Pipeline

## Context

This is a brownfield change. The current kzybala.pl Performance Lab, its routes, styling, Java/Rust repositories, benchmark assets and deployment mechanics must be inspected before implementation. The specification defines behavior and quality; it does not justify replacing working infrastructure for fashion.

## Goals

- Define a versioned result schema for JMH, Criterion, histograms and perf counters.
- Import raw results without manual transcription.
- Store provenance, comparability, confidence and evidence maturity.
- Generate charts and tables deterministically.
- Support historical runs and regression thresholds without hiding variance.

## Explicit Constraints

- Repository reality beats guessed filenames, frameworks and module names.
- Java and Rust examples must implement equivalent semantics before their numbers are compared.
- Raw benchmark output and environment metadata are primary evidence.
- Mean latency alone is not sufficient for latency-sensitive conclusions.
- Client-side browser benchmarks are educational demonstrations only unless the environment is controlled and clearly labeled.
- Existing False Sharing, Ring Buffer and JIT Pipeline work must be treated as migration inputs.

## Canonical schema

Normalize identifiers and metadata while retaining complete raw harness output. Do not force JMH and Criterion statistical models into false equivalence.

## Importer boundary

Importers parse raw artifacts into a validated intermediate representation. Invalid units, missing parameters or duplicate run ids fail fast.

## Rendering

Charts are generated from committed/imported data through deterministic transforms. Rounded display values never replace raw values.

## Regression policy

Compare only matching lab, variant, dataset, parameters, architecture class and compatible environment profiles. Thresholds may differ by metric.

## Review workflow

A run moves draft → reproduced → profiled → verified. Site badges reflect actual state and may regress if provenance is invalidated.

## Validation Strategy

1. Validate content/schema invariants at build time.
2. Run unit and integration tests for the changed platform behavior.
3. Run smoke benchmarks only to prove wiring.
4. Preserve publication-grade benchmark execution for controlled hosts.
5. Review all claims against raw evidence and comparability rules.

## Rollback

Keep migration changes additive until the new capability has parity. Existing routes and assets remain available until tests and manual review confirm replacement behavior.
