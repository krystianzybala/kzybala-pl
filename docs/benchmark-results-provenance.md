# Provenance and comparability validation (plab-003 task 5)

`scripts/benchmark-platform/results/provenance.js` adds the *chain*-level
checks on top of `schema.js`'s *shape*-level checks — spec.md's "Provenance
chain" requirement is that a value can actually be traced, not merely that
the record has the right keys.

## `validateProvenanceChain(record, { cwd })`

Runs `validateResult` first (shape); if that fails, returns its errors
directly — a chain can't be meaningfully validated on a malformed record.
On a shape-valid record, additionally checks:

- `provenance.sourceRevision` is either the literal `"unversioned"` sentinel
  (`scripts/benchmark-platform/run-id.js`'s own convention for "not in a git
  checkout") or a real-looking short/full git hash (`[0-9a-f]{7,40}`) — not
  a hand-typed placeholder like `"latest"` or `"tbd"`.
- `provenance.rawArtifactPath`, if non-null, actually **exists on disk**
  (resolved against `cwd`, defaulting to `process.cwd()`) — a path that
  doesn't resolve is worse than no path, because it looks provenanced
  without being verifiable.
- If `rawArtifactPath` is null, `evidenceMaturity` must be `"draft"` or
  `"legacy-unprovenanced"` — anything claiming `reproduced`/`profiled`/
  `verified` without a real artifact on disk is rejected.
- `provenance.environmentRef` must be present for anything above
  `draft`/`legacy-unprovenanced` — no promotion past an informal run without
  an environment capture to compare against.

## Cross-language comparability

`validateRecordPairComparability(javaRecord, rustRecord)` reuses the
existing plab-002 `scripts/benchmark-platform/comparability.js`
(`validateComparability`) rather than re-implementing it — this module
translates a canonical record pair's `comparability` blocks into that
function's expected `{ buildMode, datasetId, semanticsFixtureHash, warmup }`
shape and returns its verdict unchanged (`comparable` / `non-comparable` /
`invalid`). One set of comparability rules, one implementation.

## What this deliberately does not do

It does not check that two records' `architecture`/environment actually
*match* for a fair comparison — that's the regression module's job
(`docs/benchmark-regression-policy.md`), which compares a new run against
*compatible* history rather than an arbitrary pair. This module's job is
narrower: is this one record's own chain followable, and are two records'
comparability metadata mutually consistent.
