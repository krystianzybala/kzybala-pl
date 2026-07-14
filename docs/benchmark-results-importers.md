# Result importers (plab-003 tasks 3–4)

Four importers, one per raw-artifact shape, all in
`scripts/benchmark-platform/results/`. Every importer fails fast on
malformed input (design.md's "Importer boundary": "Invalid units, missing
parameters or duplicate run ids fail fast") — none of them silently skip a
bad entry or substitute a default value for a missing one.

## JMH — `jmh-importer.js`

`importJmhResults(document, meta)` parses a JMH `-rf json` document (an
array of per-benchmark entries, `docs/benchmark-publication-procedure.md`'s
`-rf json -rff results.json` output) into an array of canonical `scalar`
records.

- `variant` is derived from JMH's own `benchmark` field (the trailing method
  name after the fully-qualified class), via `variantFromBenchmarkName`.
- `direction` is derived from JMH's `mode`: `"thrpt"` → `higherIsBetter`,
  every other mode → `lowerIsBetter`. Never guessed from the unit string
  (`"ops/ms"` vs `"ms/op"` can't be told apart from the unit alone).
- `error` prefers JMH's own `scoreError`; if absent, it's derived as half the
  width of `scoreConfidence`.
- Throws if an entry has no `benchmark` string, or no
  `primaryMetric.scoreUnit`.

Golden-tested against `__fixtures__/jmh-sample.json` →
`__fixtures__/jmh-sample.golden.json` (`scripts/test-benchmark-results.js`).

## Criterion — `criterion-importer.js`

`importCriterionEstimates(estimates, meta)` parses one `estimates.json`
(Criterion 0.5.x's own output, in `target/criterion/<bench>/<fn>/new/`) into
one canonical `scalar` record. Prefers `median` (Criterion's own headline
statistic) and falls back to `mean` only if `median` is unusable. `unit` is
always `"ns"` — Criterion's estimates are always nanoseconds internally
regardless of what its CLI prints; unit conversion for display is the
renderer's job (`docs/benchmark-results-rendering.md`), not the importer's.
`direction` is unconditionally `lowerIsBetter` — Criterion's estimates are
always a time-per-iteration quantity, never a throughput.

Golden-tested against `__fixtures__/criterion-estimates-sample.json` →
`__fixtures__/criterion-estimates.golden.json`.

## Histogram — `histogram-importer.js`

`importHistogram(source, meta)` — a generic percentile-map importer, not
tied to one histogram library. No lab in this repository publishes a
histogram today (`docs/benchmark-platform-inventory.md`); this exists so a
future latency-focused lab (e.g. `plab-102`, `plab-605` per their OpenSpec
proposals) has a schema-conformant path from day one. Requires `p50`, `p90`,
`p99` at minimum, plus `count`/`min`/`max`/`mean` — fails fast on any
missing required percentile rather than reporting an incomplete tail.

## Perf counters — `perf-counter-importer.js`

`importPerfCounterCsv(csvText, meta)` parses the machine-readable CSV form
of `perf stat -x,<sep> ...` (documented in `man perf-stat`: one line per
event, `value,unit,event,time_running,percent_running,metric_value,metric_unit`).

**Honesty note:** this repository's benchmark hosts to date are macOS/arm64
(`docs/benchmark-platform-inventory.md`), where `perf` doesn't exist. This
importer is implemented strictly against the documented CSV format and
tested only against a synthetic fixture
(`__fixtures__/perf-stat-sample.csv`) — it has never been run against a live
`perf stat` capture. No lab publishes a perf-counter number yet. Do not cite
this importer's output as measured evidence until it has actually ingested a
real Linux `perf stat -x,` capture.
