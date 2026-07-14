# Content gate: rejecting unprovenanced production charts (plab-003 task 11)

`scripts/validate-benchmark-results.js` (`npm run validate:benchmark-results`,
wired into `npm run verify` and `.github/workflows/ci.yml`) is the automated
enforcement of spec.md's "No manual numbers" requirement at the content
level: every committed result manifest under `content/labs/*/` must be
schema-valid (`docs/benchmark-results-schema.md`) and provenance-valid
(`docs/benchmark-results-provenance.md`) before it can be considered fit to
back a chart.

## What it scans

Every `content/labs/<id>/legacy-results.json` and
`content/labs/<id>/results-manifest.json` (the latter is the naming
convention a future non-legacy manifest would use — none exists yet). For
each record found, it runs, in order:

1. `validateResult` (schema shape).
2. A check that the record's `labId` matches the directory it was found in
   — a copy-paste error that put one lab's record under another's directory
   is caught here, not silently rendered on the wrong page.
3. `validateProvenanceChain` (chain-follow-ability, including the
   `rawArtifactPath`-exists-on-disk check).

Any failure at any of these steps fails the whole gate (exit 1) with every
problem listed, not just the first.

## Verified behavior

Confirmed by deliberately setting a legacy record's `evidenceMaturity` to
`"verified"` (which requires a raw artifact it doesn't have) and re-running
the gate: it correctly failed with
`evidenceMaturity "verified" requires provenance.rawArtifactPath — only
"draft" or "legacy-unprovenanced" may have a null raw artifact`, then passed
again once reverted. This is the concrete case the gate exists for: a
`legacy-unprovenanced` record can never be silently relabeled into a
higher-trust maturity without an actual raw artifact appearing alongside it.

## What "production chart" means today

No lab's HTML currently renders a chart from `legacy-results.json` — the
existing `benchmark.md` prose tables are still what's displayed
(`docs/benchmark-results-inventory.md`). This gate protects the *data layer*
a future rendering surface will read from, so that when one is built it
inherits an already-enforced provenance guarantee rather than needing to
invent one under deadline pressure.
