# Migrating existing reference-lab data (plab-003 task 9)

## The constraint

design.md: "Existing False Sharing, Ring Buffer and JIT Pipeline work must
be treated as migration inputs." proposal.md's completion evidence: "No
benchmark number is fabricated, manually rounded into source, or presented
without provenance." Those two statements are in tension for this
repository's actual state
(`docs/benchmark-results-inventory.md`): the five existing `benchmark.md`
tables have real numbers but **no raw artifact was ever captured behind
them** — there is nothing to migrate *as evidence*, only prose to migrate
*as data*.

## What migration means here

`scripts/benchmark-platform/results/legacy-data.js` is the single source of
truth: one canonical record per row of each lab's existing "Measured data"
table, transcribed directly from the committed `benchmark.md` prose (not
re-measured, not re-rounded, not estimated). Every record is stamped:

```
evidenceMaturity: "legacy-unprovenanced"
provenance.rawArtifactPath: null
provenance.sourceRevision: "unversioned"
```

`scripts/generate-legacy-results.js` writes these out to
`content/labs/<id>/legacy-results.json`, validating each record against the
schema before writing (fails the build if a transcription produced an
invalid record).

## The drift guard

Hand-transcribing ~19 numbers by reading prose tables is exactly the kind of
step that silently drifts from its source over time. `scripts/test-benchmark-results.js`'s
`"legacy-data: every pointEstimate appears verbatim in its lab's
benchmark.md"` test re-reads each lab's actual `benchmark.md` and asserts
every transcribed `pointEstimate` still appears in it, formatted either
plainly or with `toLocaleString("en-US")` grouping (matching how the prose
already writes large numbers, e.g. `1,861,211.873`). If a future edit to
`benchmark.md` changes a number without updating `legacy-data.js` (or vice
versa), this test fails.

## What is explicitly *not* migrated

- `memory-ordering` and `mesi` have no `benchmark.md` and are absent from
  `LEGACY_RESULTS` — inventing a placeholder record for them would be
  exactly the fabrication this migration exists to avoid.
- Rust "derived ops/ms" values that the original prose computed from a
  median batch time (e.g. `cas-contention`, `thread-per-core`) are migrated
  as separate records with `error: null` and a `notes` entry stating they
  are derived, not directly measured — they are not given a fabricated
  confidence interval they never had.

## The only legitimate path off `legacy-unprovenanced`

Per `docs/evidence-maturity.md`, `legacy-unprovenanced` has no outgoing
transitions. A lab moves off it only by a maintainer actually running the
plab-002 pipeline end to end
(`docs/benchmark-publication-procedure.md`), producing a real
`results/<lab-id>/<run-id>/raw/` artifact, and importing it as a brand-new
`draft` record — the legacy record is then superseded in `benchmark.md`,
never edited in place to claim a higher maturity it didn't earn.
