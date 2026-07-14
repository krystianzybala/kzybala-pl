# Content gate: rejecting unprovenanced production charts (plab-003 task 11)

`scripts/validate-benchmark-results.js` (`npm run validate:benchmark-results`,
wired into `npm run verify` and `.github/workflows/ci.yml`) is the automated
enforcement of "no manual numbers" at the content level: every committed
result manifest under `content/labs/*/` must be schema-valid
(`docs/benchmark-results-schema.md`) and provenance-valid (hash-verified —
`docs/benchmark-results-provenance.md`) before it can be considered fit to
back a chart. Rewritten in the 2026-07-14 remediation pass to add
cross-manifest duplicate detection and derivation-cycle detection, and to
report the record set's *derived* evidence-maturity distribution rather than
a trusted stored label.

## What it scans

Every `content/labs/<id>/legacy-results.json` and
`content/labs/<id>/results-manifest.json` (the latter is the naming
convention a future non-legacy manifest would use — none exists yet). For
each record found, in order:

1. `validateResult` (schema shape, including the `canonicalResultHash`
   self-consistency check and the strict field allow-list).
2. A check that the record's `labId` matches the directory it was found in.
3. `validateProvenanceChain` (deep hash verification against the approved
   artifacts root — `scripts/benchmark-platform/results/artifacts/`).

Then, across the **combined** set of every record found in every manifest:

4. `findDuplicateIdentities` — the same lab/variant/language/harness/mode/
   `@Param` identity appearing in two different manifests (a single JMH
   document's *internal* duplicates are already caught by
   `jmh-importer.js` at import time; this catches duplicates introduced by
   combining separately-imported manifests).
5. `detectDerivationCycle` — for any record with a non-null
   `statistic.derivedFrom`, walks the chain looking for a cycle or a
   reference to an unknown `canonicalResultHash`.

Any failure at any step fails the whole gate (exit 1) with every problem
listed, not just the first. On success, it prints the derived
evidence-maturity distribution across everything it checked (e.g.
`legacy-unprovenanced=31`) — computed fresh via `deriveMaturity`, never read
from a stored field, because there isn't one.

## Verified behavior

`scripts/test-benchmark-results.js`'s `provenance:` and `evidence-maturity:`
groups exercise the same validators this gate calls, including the specific
adversarial case the audit found: a schema-valid, provenance-shape-valid
record claiming `evidence.importerCapability: "live-publication-validated"`
while produced by an importer registered `"fixture-only"` is now rejected
by `validateResult` itself, before the gate's deep provenance check even
runs — the fabrication is caught at the cheapest possible check, not left
to a downstream inspector.

## What "production chart" means today

No lab's HTML currently renders a chart from `legacy-results.json` — the
existing `benchmark.md` prose tables are still what's displayed. This gate
protects the *data layer* a future rendering surface will read from, so that
when one is built it inherits an already-enforced provenance guarantee
rather than needing to invent one under deadline pressure.
