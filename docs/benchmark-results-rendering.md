# Deterministic chart/table rendering (plab-003 task 6)

`scripts/benchmark-platform/results/render.js` — updated in the 2026-07-14
remediation pass for the new canonical-number statistic shape and derived
evidence maturity.

## Determinism

`renderTable(records, opts)` and `renderChartSeries(records, opts)` both
sort their input by an explicit key (`labId variant language harness`)
before producing output — the result is identical regardless of import
order. `renderHash(rendered)` (built on `canonical-hash.js`'s
`stableStringify`) gives a content hash of a render for a golden-file or
content-gate check that doesn't care about object key order. `opts` is
threaded through to `evidence-maturity.js`'s `deriveMaturity` (e.g.
`{ artifactsRoot }` for tests) — a render never trusts a stored maturity
label because there isn't one.

## Raw vs. display

Every table row carries **both**:

- `pointEstimate` / `error` — the exact canonical-number values
  (`numeric.js`'s `{ value, numericType }` shape) from the record's primary
  estimate, passed through untouched — never a native `Number`, never
  re-rounded.
- `display.pointEstimate` / `display.error` — `numeric.js`'s
  `formatForDisplay`, rounded to 3 significant decimals for a page. Unlike
  the v1 renderer's bare `Number(pointEstimate.toFixed(3))`, this **cannot**
  silently show `"0"` for a genuinely non-zero measurement: a value that
  would round to a display magnitude of zero gets an explicit `underflow:
  true` flag and a `"<0.001"`-style bound instead.

A caller that only reads `display` gets a clean number for a page; a caller
that needs the full-precision value (a regression comparison, an export)
still has it in the same object, as exact decimal text.

## Evidence maturity in the render

Every row also carries `evidenceMaturity` (the *derived* level — `draft`,
`reproduced`, `profiled`, `verified`, or `legacy-unprovenanced`),
`isPublishable`, and `badge` (the human-facing label from
`evidence-maturity.js`'s `badgeFor`) — computed fresh at render time, so a
rendering surface never has to re-implement the maturity derivation or
trust a value baked in at import time.

## Scope

Only `metricKind: "scalar"` records participate in a table or chart series
today. No lab publishes a `histogram` or `counter` result yet — a rendering
convention for those kinds is deliberately not invented here.
