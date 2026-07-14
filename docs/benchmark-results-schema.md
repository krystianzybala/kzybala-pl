# Canonical result schema (plab-003 task 2)

`scripts/benchmark-platform/results/schema.js` — `SCHEMA_ID =
"pl.kzybala.benchmark-results"`, `SCHEMA_VERSION = 2`. `validateResult(record)`
returns `{ valid, errors }` and never throws; every importer, the legacy
migration, and the content gate (`scripts/validate-benchmark-results.js`)
call it before trusting a record.

Rewritten in the 2026-07-14 remediation pass after an archival-readiness
audit found the v1 schema: accepted `NaN`/`Infinity`/`-Infinity` as valid
`pointEstimate` values; silently corrupted integers above
`Number.MAX_SAFE_INTEGER` (inherited from native `Number` storage); accepted
any unrecognized top-level field; never validated units or JMH modes against
a known set; and let a bare `evidenceMaturity: "verified"` string stand in
for evidence nothing in the record actually demonstrated. See
`docs/benchmark-results-provenance.md` and `docs/evidence-maturity.md` for
how those last two are fixed structurally, not just documented away.

## Canonical numeric representation

`scripts/benchmark-platform/results/numeric.js` defines the only
representation a measured value may take: `{ value: "<exact decimal
string>", numericType: "integer" | "decimal" }`. Native `Number` never
appears in `statistic` — `toCanonicalNumber` rejects `NaN`/`Infinity`/
`-Infinity` outright, preserves integers of arbitrary magnitude and decimals
of arbitrary precision as exact source text, and represents `0`/`-0` as
distinct strings (`"0"` / `"-0"`) so a negative-zero measurement is never
silently collapsed. Display rounding (`formatForDisplay`, used only by
`render.js`) never lets a genuinely non-zero value show as exactly `0` — it
returns an explicit underflow marker (e.g. `"<0.001"`) instead. See
`scripts/test-benchmark-results.js`'s `numeric:` test group for the full set
of edge cases proven (`9007199254740991/992/993`,
`12345678901234567890`, `0.000000000000000123456789`, scientific notation,
`-0`).

## Record shape

Every canonical record has these top-level fields (a strict allow-list — any
other key is rejected):

```
schemaId, schemaVersion, labId, variant, language, harness, metricKind,
unit, direction, mode, parameters, statistic, provenance, evidence,
[toolchain], [comparability], [notes]
```

`direction` is required (and enum-checked: `higherIsBetter`/`lowerIsBetter`)
for `metricKind: "scalar"` and must be `null` otherwise. `mode` (JMH's
`thrpt`/`avgt`/`sample`/`ss`) is required and enum-checked when
`harness === "jmh"`, and must be `null` for every other harness — the v1
schema derived `direction` from `mode` transiently and then discarded it;
this version stores it explicitly. `harness` and `language` are
cross-validated (`jmh` requires `java`, `criterion` requires `rust`) so a
copy-paste error can't silently mislabel a record's source language.

## `metricKind` and its `statistic` shape

- **scalar** (JMH/Criterion single-number results): `{ primary, estimates:
  { mean, median, slope }, sampleCount, percentiles, rawSamples, outliers,
  secondaryMetrics, derivedFrom }`. `mean`/`median`/`slope` are each either
  `null` or an independent `{ pointEstimate, error, errorKind,
  confidenceLevel }` estimate — **all three are retained when the source
  provides them**; `primary` only selects which one a table/chart displays
  by default, it does not discard the other two (the v1 importer collapsed
  Criterion's mean/median/slope into one generic "score" — this was the
  audit's central Criterion finding, now fixed).
- **histogram** (latency percentile data): `{ format, count, min, max, mean,
  percentiles, significantDigits, overflow, coordinatedOmission }`. `format`
  identifies the histogram implementation (e.g. `"hdrhistogram"`) — an
  unidentified format is rejected, since tail-latency claims are only as
  trustworthy as knowing what produced them.
- **counter** (perf-stat style event counts): `{ event, value, notCounted,
  notSupported, rawCountText, timeRunningNs, percentRunning, runsAggregated,
  stddevPercent, derivedMetric }`, plus `unit`. `derivedMetric` carries
  `perf`'s own computed ratio (e.g. `metric_value`/`metric_unit` — "insn per
  cycle") separately from the raw counted `value`, explicitly labeled as
  derived rather than merged into it.

## Units

`scripts/benchmark-platform/results/units.js` is a versioned registry of
known unit strings. `unit` must be a registry key — an unrecognized unit
(e.g. a typo, or a unit no importer has ever emitted) is a rejected record,
not silently accepted free text.

## `evidence`

Replaces v1's single `evidenceMaturity` string with nine independent
dimensions (`legacy`, `correctness`, `environment`, `reproduction`,
`profiling`, `comparability`, `reviewer`, `importerCapability`, `warnings`).
A maturity badge (`draft`/`reproduced`/`profiled`/`verified`/
`legacy-unprovenanced`) is *derived* from these plus the hash-verified
provenance chain — never stored, never trusted as written. See
`docs/evidence-maturity.md` for the full derivation and why a caller cannot
obtain `"verified"` merely by writing the word.

## Provenance shape

Ten hash-reference fields plus `rawArtifact`, `canonicalResultHash`,
`sourceCommit`, `command`, `capturedAt`, `importerVersion` — every field
required to be *present* (even if `null`), so a reviewer can tell from the
record itself whether a link was captured or simply never asked for.
`canonicalResultHash` is recomputed from the record's own `statistic` on
every `validateResult` call and compared against the stored value — a
`statistic` edited after import without recomputing the hash is caught as a
tamper/mismatch, not silently accepted (see `docs/benchmark-results-provenance.md`
for the full chain).

## Canonical serialization (for hashing)

`scripts/benchmark-platform/results/canonical-hash.js`'s `stableStringify`:
UTF-8 encoding (Node's default), recursively sorted object keys, array order
preserved as given (callers sort first when order isn't semantically
meaningful — `render.js` does), compact single-line output (no newline
policy to be inconsistent about), and every string passed through Unicode
NFC normalization before serialization so two byte-different but
canonically-equivalent strings hash identically. Numeric leaves are already
plain strings (`numeric.js`'s canonical shape), so no numeric-string
normalization step is needed at hash time — `normalizeNumericString` is the
single place that decision is made, once, at parse time.

## Versioning

A record whose `schemaVersion` doesn't match `SCHEMA_VERSION` (or whose
`schemaId` doesn't match `SCHEMA_ID`) fails validation outright. `migrateRecord`
is the entry point for upgrading an older schema version; the only
registered prior version (`1`, this repository's own pre-remediation shape)
has **no lossless migration path** — v1 stored measured values as native
`Number`, which had already silently corrupted any value that couldn't
round-trip through a double, so there is no way to recover the original
source text. `migrateRecord` says so explicitly rather than guessing;
`scripts/test-benchmark-results.js` proves it throws instead of silently
approximating. A future schema version with a real prior-version migration
path should add a case to `migrateRecord`, not bypass `validateResult`'s
version check.
