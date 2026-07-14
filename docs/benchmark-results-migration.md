# Migrating existing reference-lab data (plab-003 task 9)

## The constraint

The five existing `benchmark.md` tables have real numbers but **no raw
artifact was ever captured behind them** — there is nothing to migrate *as
evidence*, only prose to migrate *as data*.

## What migration means here

`scripts/benchmark-platform/results/legacy-data.js` is the single source of
truth: one canonical record per row of each lab's existing "Measured data"
table, transcribed directly from the committed `benchmark.md` prose (not
re-measured, not re-rounded, not estimated) — only the *representation*
changed in the 2026-07-14 remediation pass (native `Number` → canonical
decimal string; one generic `error` → a named estimate under
`statistic.estimates`), never the transcribed values themselves. Every
record is built via `record-builder.js`'s `buildLegacyProvenance`/
`buildLegacyEvidence`, which stamp:

```
evidence.legacy: true
evidence.importerCapability: "fixture-only"
provenance.rawArtifact: null
provenance.sourceCommit.revision: "unversioned"
(every other provenance hash-reference field: { ref: null, hash: null })
```

`schema.js` additionally **rejects** any legacy record that tries to set a
non-baseline value on any of the above, or on any other `evidence`
dimension — a legacy record cannot be upgraded by adding fabricated
references, structurally, not just by convention.
`evidence-maturity.js`'s `deriveMaturity` treats `evidence.legacy === true`
as an unconditional, terminal short-circuit regardless of what else is set,
as defense in depth on top of that schema rejection.

`scripts/generate-legacy-results.js` writes these out to
`content/labs/<id>/legacy-results.json`, validating each record against the
schema before writing (fails the build if a transcription produced an
invalid record).

## The drift guard — what it proves, and what it does not

Hand-transcribing ~19 numbers by reading prose tables is exactly the kind of
step that silently drifts from its source over time.
`scripts/test-benchmark-results.js`'s `"legacy-data: every pointEstimate
appears verbatim in its lab's benchmark.md"` test re-reads each lab's actual
`benchmark.md` and asserts every transcribed `pointEstimate` still appears
in it, formatted either plainly or with `toLocaleString("en-US")` grouping.

**This proves transcription fidelity only — it does not prove, and must
never be described as proving, benchmark validity or reproducibility.** The
underlying `benchmark.md` numbers themselves have no raw JMH/Criterion
artifact behind them; the drift guard only guarantees `legacy-data.js` still
matches the prose it was transcribed from, not that the prose describes a
real, controlled, reproducible measurement. It also covers `pointEstimate`
only — the computed CI half-widths present for a few rows (e.g.
`cache-hierarchy`'s Rust entries, derived as `(upper - lower) / 2` from
bounds that *are* verbatim in `benchmark.md`) are arithmetic derivations,
not verbatim strings, and are not independently drift-checked. If a future
edit to `benchmark.md` changes a number without updating `legacy-data.js`
(or vice versa), the `pointEstimate` check fails; a change to only the CI
bounds could still drift silently.

## What is explicitly *not* migrated

- `memory-ordering` and `mesi` have no `benchmark.md` and are absent from
  `LEGACY_RESULTS` — inventing a placeholder record for them would be
  exactly the fabrication this migration exists to avoid.
- Rust "derived ops/ms" values that the original prose computed from a
  median batch time (e.g. `cas-contention`, `thread-per-core`) are migrated
  as separate records with `error: null` and a `notes` entry stating they
  are derived, not directly measured.

## Legacy-result restrictions (enforced, not aspirational)

- **Cannot be a regression baseline**: `regression.js`'s `findBaseline`
  only considers candidates whose *derived* maturity is `"verified"` —
  `legacy-unprovenanced` never qualifies, by construction.
- **Cannot contribute to a verified summary or a Java-vs-Rust conclusion**:
  no such rendering/conclusion feature exists in this repository yet
  (confirmed by the audit); when one is built, it must consume `deriveMaturity`
  the same way `regression.js` does, not a stored label.
- **Visibly labeled when rendering support is added**: `badgeFor(
  "legacy-unprovenanced")` returns a label that states the restriction
  outright ("cannot be a regression baseline or contribute to a
  verified/Java-vs-Rust conclusion") — any future rendering surface that
  displays these records is expected to surface that label, not a bare
  number.

## The only legitimate path off `legacy-unprovenanced`

A lab moves off it only by a maintainer actually running the plab-002
pipeline end to end, producing a real `results/<lab-id>/<run-id>/raw/`
artifact through an importer whose `capability-registry.js` ceiling has
been raised past `"fixture-only"` after genuine live-hardware validation,
and importing it as a brand-new `draft` record — the legacy record is then
superseded in `benchmark.md`, never edited in place to claim a higher
maturity it didn't earn.
