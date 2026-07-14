# Deterministic chart/table rendering (plab-003 task 6)

`scripts/benchmark-platform/results/render.js` — design.md's "Rendering"
constraint: "Charts are generated from committed/imported data through
deterministic transforms. Rounded display values never replace raw values."

## Determinism

`renderTable(records)` and `renderChartSeries(records)` both sort their
input by an explicit key (`labId variant language harness`) before
producing output — the result is identical regardless of the order records
were imported or passed in. `renderHash(rendered)` (built on the existing
`stableStringify` from `scripts/benchmark-platform/run-id.js`) gives a
content hash of a render for a golden-file or content-gate check that
doesn't care about object key order.

## Raw vs. display

Every table row carries **both**:

- `pointEstimate` / `error` — the exact, unrounded numbers from the
  underlying `statistic`, passed through untouched.
- `display.pointEstimate` / `display.error` — the same numbers rounded to 3
  significant decimal places (`toFixed(3)`), matching the precision already
  used across every existing `benchmark.md` table
  (`docs/benchmark-results-inventory.md`).

A caller that only reads `display` gets a clean number for a page; a caller
that needs the full-precision value (a regression comparison, an export)
still has it in the same object. Rounding never replaces the source value.

## Scope

Only `metricKind: "scalar"` records participate in a table or chart series
today. No lab publishes a `histogram` or `counter` result yet
(`docs/benchmark-results-inventory.md`), so a rendering convention for those
kinds is deliberately not invented here — design.md's non-goal boundary
("Implement all laboratory code in this change") applies to rendering
surfaces the same way it applies to lab domain code.
