# Design: Unified Laboratory Framework

## Context

This is a brownfield change. The current kzybala.pl Performance Lab, its routes, styling, Java/Rust repositories, benchmark assets and deployment mechanics must be inspected before implementation. The specification defines behavior and quality; it does not justify replacing working infrastructure for fashion.

## Goals

- Create reusable page sections for theory, visualization, Java, Rust, benchmark setup, results, profiler evidence and exercises.
- Define a lab content schema and validation rules.
- Support side-by-side and sequential comparison without forcing identical source structure.
- Provide accessible tables and charts with textual fallbacks.
- Add a per-lab verification checklist and maturity badge.

## Explicit Constraints

- Repository reality beats guessed filenames, frameworks and module names.
- Java and Rust examples must implement equivalent semantics before their numbers are compared.
- Raw benchmark output and environment metadata are primary evidence.
- Mean latency alone is not sufficient for latency-sensitive conclusions.
- Client-side browser benchmarks are educational demonstrations only unless the environment is controlled and clearly labeled.
- Existing False Sharing, Ring Buffer and JIT Pipeline work must be treated as migration inputs.

## Component boundary

Create framework components around content semantics rather than individual labs: LabHeader, Hypothesis, MechanismVisualization, ImplementationTrack, BenchmarkConfig, ResultSet, EvidencePanel, Pitfalls and Exercises.

## Content schema

Validate required fields at build time. A lab missing hypothesis, commands, raw data links or evidence status must fail the content-quality gate rather than silently render placeholders.

## Comparison model

Allow Java and Rust sections to explain distinct runtime models. Side-by-side code is optional; semantic equivalence and measured configuration are mandatory.

## Visualization model

Prefer deterministic diagrams and data-driven plots. Decorative animation must not obscure values or become the only explanation.

## Extensibility

New labs should mostly add content and benchmark adapters, not copy page structure or invent new result formats.

## Validation Strategy

1. Validate content/schema invariants at build time.
2. Run unit and integration tests for the changed platform behavior.
3. Run smoke benchmarks only to prove wiring.
4. Preserve publication-grade benchmark execution for controlled hosts.
5. Review all claims against raw evidence and comparability rules.

## Rollback

Keep migration changes additive until the new capability has parity. Existing routes and assets remain available until tests and manual review confirm replacement behavior.
