# Result importers (plab-003 tasks 3–4)

Four importers, one per raw-artifact shape, all in
`scripts/benchmark-platform/results/`. Every importer fails fast on
malformed input — none silently skip a bad entry or substitute a default
value for a missing one. Rewritten in the 2026-07-14 remediation pass after
an audit found the v1 importers silently dropped fields (JMH `@Param`,
`secondaryMetrics`, `scorePercentiles`, raw samples, JVM executable path,
measurement-iteration count; Criterion's `slope` estimate was never even
read; perf's `metric_value`/`metric_unit` columns), mislabeled a JMH
sample-mode mean as a "median," and performed no duplicate-entry detection
despite `design.md` explicitly requiring one.

## Capability levels

`scripts/benchmark-platform/results/capability-registry.js` is the
enforced ceiling on what evidence-maturity level a record produced by a
given importer can ever reach (`docs/evidence-maturity.md`):

| Level | Meaning |
|---|---|
| `fixture-only` | Only ever exercised against a synthetic fixture file — never ingested genuine output captured from a live run of the real tool on real hardware. |
| `live-smoke-validated` | Has ingested a real capture at least once, on a documented host, without full publication-grade rigor. |
| `live-publication-validated` | Validated against real captures on the specific controlled hardware/profile this repository publishes from. |

**Every importer in this repository is registered `fixture-only` today** —
including JMH and Criterion. None has ever ingested genuine tool output;
each is exercised only against `__fixtures__/`. Raising an importer's
ceiling is a maintainer editing `capability-registry.js` after documenting
the actual validation that justifies it — not something a content author
can do by hand-editing a record's `evidence.importerCapability` (`schema.js`
rejects a claim above the registered ceiling; see
`docs/evidence-maturity.md`).

## JMH — `jmh-importer.js`

`importJmhResults(document, meta)` parses a JMH `-rf json` document into
canonical `scalar` records, preserving:

- `mode` — stored explicitly on the record (`thrpt`/`avgt`/`sample`/`ss`);
  an unrecognized mode string is rejected at import, not silently mapped to
  a guessed direction.
- `direction` — derived from `mode` only (`thrpt` → `higherIsBetter`,
  everything else → `lowerIsBetter`); never guessed from the unit string.
- `parameters` — every `@Param` key/value pair, verbatim.
- `statistic.estimates.mean` — JMH's reported `score` is always the
  arithmetic mean of the underlying iteration/sample results, **for every
  mode including `sample`**; it is never labeled `"median"` (the v1
  importer's mislabeling, now fixed). The true median for `sample` mode
  lives separately, verbatim, in `statistic.percentiles["p50.0"]`.
- `statistic.secondaryMetrics` — every JMH secondary metric, each with its
  own `pointEstimate`/`error`/`unit`.
- `statistic.percentiles` / `statistic.rawSamples` — `scorePercentiles` and
  flattened `rawData`, preserved verbatim, not just counted.
- `toolchain.jvmExecutable` / `comparability.measurement` — the JVM binary
  path and measurement-iteration count/time, previously dropped entirely.
- **Duplicate detection** — two entries sharing the same
  benchmark+mode+`@Param` identity anywhere in one document fail the whole
  import fast, whether or not their reported scores agree (a contradictory
  duplicate is rejected exactly like an identical one — there is no
  "which one wins" logic).

Never converts throughput into latency or vice versa — `thrpt` and `avgt`
scores pass through with their own unit and derived direction, never merged
into one generic "score."

Golden-tested against `__fixtures__/jmh-sample.json` →
`__fixtures__/jmh-sample.golden.json`.

## Criterion — `criterion-importer.js`

`importCriterionEstimates(estimates, meta)` parses one `estimates.json`
into one canonical `scalar` record, reading `mean`, `median`, **and**
`slope` **independently** — the v1 importer never read `slope` at all and
collapsed whichever of mean/median won into one generic score. All three
that are present in the source are retained in full, each with its own
error/confidence-interval; `primary` (median-preferred, matching Criterion's
own CLI headline) only selects which one a table displays by default.

Also preserves: outlier metadata (caller-supplied — Criterion's outlier
fences live outside `estimates.json`, in `benchmark.json`/`tukey.json`,
which this importer doesn't consume), throughput mode (`meta.throughput`
correctly flips `unit`/`direction` to `higherIsBetter` instead of assuming
every Criterion run is a latency measurement), Criterion/rustc version,
target triple, and baseline/comparison metadata (all caller-supplied, since
none of it lives in `estimates.json` itself).

Rejects: a malformed (non-object) document, a document with no usable
mean/median/slope estimate at all, and a contradictory confidence interval
(`lower_bound > upper_bound`).

Golden-tested against `__fixtures__/criterion-estimates-sample.json` →
`__fixtures__/criterion-estimates.golden.json`.

## Histogram — `histogram-importer.js`

`importHistogram(source, meta)` — a generic percentile-map importer, not
tied to one histogram library, but now requires `source.format` (e.g.
`"hdrhistogram"`) to identify the implementation; an unidentified format is
rejected rather than silently accepted, since a tail-latency claim is only
as trustworthy as knowing what produced it. Also preserves
`significantDigits`, `overflow` (saturation metadata), and
`coordinatedOmission` (correction status + method) — none of which existed
in the v1 shape. Requires `p50`/`p90`/`p99` at minimum. Golden-tested
against `__fixtures__/histogram-sample.json` →
`__fixtures__/histogram-sample.golden.json`.

## Perf counters — `perf-counter-importer.js`

`importPerfCounterCsv(csvText, meta)` parses the machine-readable CSV form
of `perf stat -x,<sep> ...`, now reading the **full documented 8-field**
positional layout (`value,unit,event,time_running_ns,percent_running,
metric_value,metric_unit,stddev_percent`) — the v1 importer only ever
destructured 5 of these, silently dropping `metric_value`/`metric_unit`
even though the bundled fixture has always contained a row exercising them.
`metric_value`/`metric_unit` (perf's own derived ratio, e.g. "insn per
cycle") are preserved under `statistic.derivedMetric`, explicitly separate
from the raw counted `value`. An unparseable non-comment row is now
**rejected**, not silently filtered out. A duplicate event within one
capture fails fast.

**Honesty note, unchanged and load-bearing:** this repository's benchmark
hosts to date are macOS/arm64, where `perf` does not exist. This importer
is implemented strictly against the documented CSV format and tested only
against a synthetic fixture — it has never ingested a live `perf stat`
capture on real Linux hardware, and its `IMPORTER_CAPABILITY` stays
`"fixture-only"` regardless of how complete its field coverage gets. Nothing
in this remediation pass fabricates that validation:
`scripts/test-benchmark-results.js` proves a record built purely from this
importer can never derive to `"verified"` (the capability-ceiling check
rejects the claim outright even if every other evidence dimension is set).
Do not cite this importer's output as measured evidence until it has
actually ingested a real Linux `perf stat -x,` capture — and until then, do
not raise its registry entry in `capability-registry.js`.
