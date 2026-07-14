# Historical-run comparison and regression thresholds (plab-003 task 8)

`scripts/benchmark-platform/results/regression.js` — design.md's
"Regression policy": "Compare only matching lab, variant, dataset,
parameters, architecture class and compatible environment profiles.
Thresholds may differ by metric."

## Compatibility

`isCompatible(a, b)` requires an exact match on: `labId`, `variant`,
`language`, `harness` (== "parameters" here — same benchmark method, same
harness), `comparability.datasetId`, `comparability.buildMode`,
`comparability.architecture`, and `provenance.environmentRef`. Two runs on
differently-configured hosts (different `environmentRef`) are never silently
treated as comparable, even if every other field matches.

## Baseline selection

`findBaseline(newRecord, history)` only considers **`verified`** historical
records as eligible baselines — an unreviewed `draft` is not a legitimate
thing to regress against (`docs/evidence-maturity.md`'s review workflow).
Among compatible, verified candidates, it picks the one with the latest
`provenance.capturedAt`, never by array position.

## Thresholds

`compareToHistory(newRecord, history, thresholds)` computes the exact
relative delta between the new record and its baseline (`delta`, unrounded),
then classifies it against a threshold — `thresholds[record.unit]` if
supplied, else `DEFAULT_RELATIVE_THRESHOLD` (5%, a documented default, not
per-lab-tuned). Classification respects `record.direction`
(`higherIsBetter`/`lowerIsBetter` — set by the JMH/Criterion importers, see
`docs/benchmark-results-importers.md`), so a throughput drop and a latency
increase are both correctly reported as `"regression"` even though one is a
smaller number and the other is a larger one.

Four possible statuses: `insufficient-history` (no compatible verified
baseline exists), `regression`, `improvement`, `stable`. Never rounds
`delta` away — the exact relative change is always in the result, even when
the status is `"stable"`.

## What this does not do yet

No lab in this repository has a `verified` record yet (every existing
number is `legacy-unprovenanced`,
`docs/benchmark-results-migration.md`) — this module has no real historical
data to compare against today. It is exercised by
`scripts/test-benchmark-results.js`'s synthetic fixtures, not a live
regression run, until the first lab actually completes the
draft → reproduced → verified workflow.
