# Provenance chain verification (plab-003 task 5)

`scripts/benchmark-platform/results/provenance.js` — rewritten in the
2026-07-14 remediation pass. The v1 version only checked that
`rawArtifactPath` **existed on disk**, resolved with a naive string
concatenation against `cwd` — an audit confirmed this accepted
`../../../../etc/hosts` as a valid raw artifact for a `"verified"` record,
and no other provenance link (environment/toolchain/dataset/profile/
correctness-gate/profiling/rendered-artifact) was ever content-hashed at
all. This version resolves every artifact reference against one approved
root and content-hashes what it accepts.

## The approved artifacts root

`defaultArtifactsRoot()` — `scripts/benchmark-platform/results/artifacts/`.
Every artifact reference in `provenance` (`rawArtifact.path`, and every
`{ ref, hash }` field's `ref`) is a path **relative to this root**, never an
absolute path and never a path that traverses outside it.

## `resolveWithinRoot(relativePath, root)`

Rejects, in order:

- an empty or non-string path,
- an **absolute path**,
- lexical `..` traversal that resolves outside the root (including nested
  traversal like `sub/../../../../etc/passwd`),
- a path that **does not exist** under the root,
- a **symlink** that, once resolved (`realpathSync`), points outside the
  root — a symlink physically located inside the approved root whose target
  escapes it is still rejected,
- anything that is **not a regular file** (a directory, device, socket,
  etc.),
- anything over `MAX_ARTIFACT_BYTES` (50 MiB).

Every one of these is a proven adversarial test in
`scripts/test-benchmark-results.js`'s `provenance:` group, including a real
symlink pointing at `/etc/hosts` under
`scripts/benchmark-platform/results/__fixtures__/artifacts/escape-link`.

## Hash verification

`hashArtifactFile` / `verifyRawArtifact` / `verifyHashRefField` content-hash
(SHA-256) whatever `resolveWithinRoot` accepts and compare it against the
hash recorded on the record. `verifyProvenanceChain(record, opts)` runs this
for every one of the ten required chain links —
`rawArtifact`, `dataset`, `semanticFixture`, `environmentManifest`,
`toolchainManifest`, `benchmarkProfile`, `correctnessGate`,
`profilingArtifact`, `renderedArtifact`, plus `sourceCommit.revision`
format-validation — and returns `{ complete, reasons }`. `complete` is
`true` only when every required link resolves and matches; this is exactly
the bar `evidence-maturity.js`'s `deriveMaturity` requires for `"verified"`.

**Changing any upstream artifact after import invalidates downstream
provenance automatically**: `scripts/test-benchmark-results.js` proves this
by importing a fully-evidenced record, then overwriting its raw artifact
file's bytes and re-running `deriveMaturity` — the record's maturity drops
from `"verified"` back to `"draft"` with an explicit
`content hash mismatch` reason, with no code path that lets a stale hash
keep asserting trust.

The record's own measured value is covered too:
`canonical-hash.js`'s `computeCanonicalResultHash(statistic)` is what
`schema.js`'s `validateResult` recomputes and compares against
`provenance.canonicalResultHash` on every call — the v1 pipeline's only
SHA-256 (`run-id.js`'s `computeRunId`) explicitly excluded `statistic`, so a
published number could be edited post-hoc with nothing to detect it. That
gap is closed here, not by adding a second unrelated hash, but by making the
one hash that matters actually cover the measurement.

## Missing-link and cycle detection

A `{ ref: null, hash: "<something>" }` (a hash with nothing to hash) is
rejected as a missing link, not silently trusted.
`detectDerivationCycle(record, resolveByHash)` walks a chain of
`statistic.derivedFrom.sourceRecordHash` references looking for either a
repeated hash (a cycle) or a hash that doesn't resolve to any known record
(a missing link) — used by the content gate across the full combined
manifest set.

## `validateProvenanceChain(record, { artifactsRoot })`

Runs `validateResult` first (shape, including the `canonicalResultHash`
self-check); if that fails, returns its errors directly. On a shape-valid
record, runs the deep hash verification above and returns
`{ valid, errors, complete }`.

## Cross-language comparability

`validateRecordPairComparability(javaRecord, rustRecord)` is unchanged from
v1: it reuses the existing plab-002
`scripts/benchmark-platform/comparability.js` (`validateComparability`)
rather than re-implementing it.

## What this deliberately does not do

It does not decide whether two records' hardware/environment are compatible
for a *regression* comparison — that is `regression.js`'s
`compatibilityKey`/`isCompatible` (`docs/benchmark-regression-policy.md`),
which references this module's `environmentManifest` hash rather than an
unverified label. This module's job is narrower: is this one record's own
chain followable and hash-consistent.
