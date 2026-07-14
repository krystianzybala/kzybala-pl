# Historical-run comparison and regression thresholds (plab-003 task 8)

`scripts/benchmark-platform/results/regression.js` — rewritten in the
2026-07-14 remediation pass. The v1 `isCompatible` substituted a single
opaque `provenance.environmentRef` string-equality check for roughly eight
of the required hardware/toolchain compatibility dimensions, and
`compareToHistory` had no absolute threshold, no noise-floor/uncertainty
awareness, no minimum-run-count requirement, and no baseline-age policy — an
audit confirmed a "regression" could fire on pure measurement noise.

## Compatibility key

`compatibilityKey(record)` extracts every dimension this repository's
tooling can capture directly and equality-checks it exactly: `labId`,
`variant`, `metricKind`, `unit`, `mode`, `statisticKind` (the record's
`statistic.primary`), `datasetId`, `semanticsFixtureHash`,
`benchmarkProfileRef`/`Hash`, `architecture`, `buildMode`, JVM
vendor/version/flags, Rust compiler version/target triple.

Dimensions this repository **cannot yet independently extract** per-field —
CPU feature set, NUMA topology, SMT/turbo state, CPU affinity, OS+kernel
build, and native/container/VM/emulation status beyond one coarse
classification — are folded into a **cryptographic reference** instead of
fabricated per-field detection: `environmentManifestHash`. Two records are
only compatible on the entire hardware/environment bundle when they
reference the exact same hash-verified environment-manifest snapshot
(`provenance.js`). This is an honest choice, not a shortcut:
`scripts/benchmark-platform/environment.js` (plab-002) genuinely does not
capture CPU feature flags, NUMA topology, SMT/turbo state, or container
detection today, and this module does not pretend otherwise. When
environment capture is extended, the compatibility key's shape does not need
to change — it already references the manifest that would carry the new
fields.

`isCompatible(a, b)` additionally requires:

- both records' `evidence.environment === "native-controlled"` — a
  container, VM, or emulated run is **never** compatible with anything, on
  either side (no cross-machine or cross-virtualization comparison is
  silently allowed),
- `datasetId`, `semanticsFixtureHash`, and `environmentManifestHash` are all
  **non-null** on both sides — two records that are both silently missing
  this information are treated as non-comparable, not "compatible by
  coincidence."

## Baseline selection

`findBaseline(newRecord, history, opts)` only considers records whose
**derived** maturity (`evidence-maturity.js`'s `deriveMaturity`, never a
stored string) is `"verified"`, that are compatible per the key above, and
that are not older than `maxBaselineAgeDays` (default 180 days) — a stale
baseline is silently excluded rather than distorting a comparison against a
long-superseded implementation. Among the remaining candidates, it picks the
one with the latest `provenance.capturedAt`, never by array position.

## Classification

`compareToHistory(newRecord, history, thresholds, opts)`:

1. **Minimum run count** — if `newRecord.evidence.reproduction.completed`
   is below the configured minimum (default 1), returns
   `"insufficient-runs"` without attempting a comparison at all.
2. **Baseline lookup** — no compatible, verified, non-stale baseline →
   `"insufficient-history"`.
3. **Noise floor** — computes the combined measurement uncertainty
   (`newRecord`'s + baseline's approximate `error`, both read from the exact
   canonical decimal string, never a pre-rounded display value). If the raw
   absolute delta is within that combined noise floor, the result is
   `"stable"` **regardless of how large the raw relative percentage looks**
   — an uncertain delta is never called a regression.
4. **Thresholds** — outside the noise floor, classifies against a relative
   threshold (`thresholds[unit].relative`, default 5%) and/or an absolute
   threshold (`thresholds[unit].absolute`, unset by default) — either one
   exceeding its bound in the worse direction is a `"regression"`; in the
   better direction, an `"improvement"`.

Direction (`higherIsBetter`/`lowerIsBetter`) is respected throughout, so a
throughput drop and a latency increase are both correctly `"regression"`
even though one is a smaller number and the other larger.

## An incompatible baseline never blocks

`findBaseline` filters through `isCompatible` before any comparison exists —
there is no code path that produces a `"regression"` verdict against a
baseline that failed the compatibility check; the worst case is
`"insufficient-history"` (informational).

## What this does not do yet

No lab in this repository has a real `"verified"` record yet — every
existing number is `"legacy-unprovenanced"`
(`docs/benchmark-results-migration.md`), and every importer's
`importerCapability` ceiling is `"fixture-only"`
(`docs/benchmark-results-importers.md`), which alone prevents `"verified"`
regardless of anything else. This module is exercised end-to-end by
`scripts/test-benchmark-results.js`'s synthetic fixtures — including a live
demonstration that tampering with a raw artifact after import correctly
degrades a record out of baseline eligibility — not by a real regression run,
until the first lab actually completes the workflow with a live-validated
importer.
