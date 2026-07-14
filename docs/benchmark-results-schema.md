# Canonical result schema (plab-003 task 2)

`scripts/benchmark-platform/results/schema.js` — `SCHEMA_VERSION = 1`.
`validateResult(record)` returns `{ valid, errors }` and never throws; every
importer, the legacy migration, and the content gate
(`scripts/validate-benchmark-results.js`) call it before trusting a record.

## Record shape

Every canonical record has these top-level fields:

```
schemaVersion, labId, variant, language, harness, metricKind, unit,
statistic, provenance, evidenceMaturity
```

`comparability`, `direction`, `toolchain`, and `notes` are present on
records that need them (importers populate them) but are not required by
the schema for every `metricKind` — a `counter` record, for instance, has no
single "better direction."

## `metricKind` and its `statistic` shape

- **scalar** (JMH/Criterion single-number results): `{ pointEstimate, error,
  errorKind, statisticName, sampleCount }`. `statisticName` is `"mean"` or
  `"median"` — JMH's default is mean (except `sample` mode), Criterion's
  default is a bootstrap median
  (`docs/benchmark-platform-inventory.md`'s cross-tool note); the importer
  records which one it actually got, never assumes.
- **histogram** (latency percentile data — no lab publishes one yet):
  `{ count, min, max, mean, percentiles: { p50, p90, p99, ... } }`.
- **counter** (perf-stat style event counts): `{ value, event }`, plus
  `unit`.

## `evidenceMaturity`

`draft | reproduced | profiled | verified | legacy-unprovenanced` — see
`docs/evidence-maturity.md` for the transition rules. The schema enforces
one hard rule tied to this field: **`reproduced`, `profiled`, and `verified`
require a non-null `provenance.rawArtifactPath`.** `draft` and
`legacy-unprovenanced` are the only two maturities allowed to have no raw
artifact — `draft` because it's an informal, not-yet-reproduced run;
`legacy-unprovenanced` because, for the five existing labs
(`docs/benchmark-results-inventory.md`), no raw artifact was ever captured
and none may be invented now.

## Provenance shape

`{ rawArtifactPath, sourceRevision, command, environmentRef,
importerVersion, capturedAt }` — every field required to be *present* (even
if `null`), per spec.md's "Provenance chain" requirement: a reviewer must be
able to tell from the record itself whether a field was captured or simply
never asked for. `docs/benchmark-results-provenance.md` covers how this
chain is actually validated (not just shape-checked).

## Versioning

A record whose `schemaVersion` doesn't match `SCHEMA_VERSION` fails
validation outright — there is no partial/best-effort reading of an
unrecognized schema version. Bumping `SCHEMA_VERSION` and adding a migration
path for existing committed manifests (`content/labs/*/legacy-results.json`)
is required work for the change that next needs a new field, not an
afterthought.
