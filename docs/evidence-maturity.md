# Evidence maturity (plab-003 task 7)

`scripts/benchmark-platform/results/evidence-maturity.js` — rewritten in the
2026-07-14 remediation pass. The v1 version stored `evidenceMaturity` as a
bare string a caller could set directly, with a legality table
(`ALLOWED_TRANSITIONS`) that was **never actually consulted by the content
gate** — an audit constructed a schema-valid, provenance-shape-valid record
with `evidenceMaturity: "verified"` and a fabricated
`rawArtifactPath`/`sourceRevision`/`environmentRef` and watched it pass both
validators cleanly. There is no stored maturity field anymore.

## Independent evidence dimensions

`schema.js`'s `evidence` object replaces the single string with nine
independently-set-and-validated dimensions:

| Dimension | Values | What it means |
|---|---|---|
| `legacy` | boolean | Pre-plab-003 transcribed data with no raw artifact — terminal, see below. |
| `correctness` | `passed` / `failed` / `not-run` | plab-002 correctness-gate outcome for this variant. |
| `environment` | `native-controlled` / `native-uncontrolled` / `container` / `vm` / `emulated` / `unknown` | Execution environment classification. |
| `reproduction` | `{ required, completed }` | Independent re-run count, tracked as two numbers, not a single "reproduced" flag. |
| `profiling` | `present` / `absent` | Whether profiler/perf-counter evidence is attached. |
| `comparability` | `validated` / `non-comparable` / `invalid` / `not-applicable` | Cross-language comparability status. |
| `reviewer` | `{ approvedBy, approvedAt }` or `null` | Recorded human sign-off with identity and timestamp. |
| `importerCapability` | `fixture-only` / `live-smoke-validated` / `live-publication-validated` | The capability ceiling of the importer that produced this record (see below) — schema-validated against `capability-registry.js`, not self-declared. |
| `warnings` | `string[]` | Any unresolved validation concerns. |

These are independent by design: a record can be `profiled` without a fresh
`reproduced` run this cycle, or have `reproduction.completed` regress
without losing its recorded `reviewer` — the v1 single-string model could
not represent any of this without an artificial total ordering.

## `deriveMaturity(record, opts)` — badge is computed, never stored

```
legacy: true  ─────────────────────────────► "legacy-unprovenanced" (terminal, unconditional)
otherwise, ALL of the following required for "verified":
  - provenance chain complete and hash-verified (provenance.js)
  - evidence.correctness === "passed"
  - evidence.environment === "native-controlled"
  - evidence.reproduction.completed >= evidence.reproduction.required >= 1
  - evidence.profiling === "present"
  - evidence.comparability === "validated" | "not-applicable"
  - evidence.reviewer !== null
  - evidence.importerCapability === "live-publication-validated"
  - evidence.warnings.length === 0
missing (provenanceComplete && reproduction && profiling) → "profiled"
missing (provenanceComplete && reproduction)               → "reproduced"
otherwise                                                    → "draft"
```

`deriveMaturity` is called fresh every time — by `render.js` for display, by
`regression.js` for baseline eligibility, by the content gate for its
summary. **Nothing writes a maturity label into a record and nothing reads
one back as authoritative.** `scripts/test-benchmark-results.js` proves each
of the nine conditions individually blocks `"verified"` when unmet
(`evidence-maturity: <condition> blocks verified`), and separately proves a
fully-evidenced record *does* reach `"verified"` when every condition
genuinely holds — the mechanism is exercised both ways, not just the
negative side.

## `legacy-unprovenanced` is a true terminal state

`evidence.legacy === true` short-circuits `deriveMaturity` unconditionally —
no other field, however complete, changes the outcome. `schema.js`
additionally rejects a legacy record that tries to carry non-baseline
evidence or provenance hash references at all ("cannot be upgraded by
adding fabricated references" — the audit's requirement, enforced as a hard
schema rejection, not just an unused check). The only legitimate path off
`legacy-unprovenanced` is a brand-new `draft` record produced by actually
running the plab-002 pipeline (`docs/benchmark-results-migration.md`) — the
legacy record is *superseded*, never edited in place.

## `importerCapability` is a registered ceiling, not a per-record claim

`scripts/benchmark-platform/results/capability-registry.js` is the single
source of truth for what each importer module is actually capable of
(`fixture-only` for every importer in this repository today — see
`docs/benchmark-results-importers.md`). `schema.js` rejects any record whose
`evidence.importerCapability` exceeds the ceiling registered for the
importer named in `provenance.importerVersion`. A hand-edited JSON file
cannot claim `"live-publication-validated"` while its importer is still
`fixture-only` — raising that ceiling is a maintainer editing
`capability-registry.js` after real validation, an auditable code change,
not a per-record claim.

## Badges

`badgeFor(level)` returns `{ label, isPublishable }` — `draft` is the only
level marked `isPublishable: false`; everything else, including
`legacy-unprovenanced`, may appear on a page as long as its badge is honest
about what it is (`legacy-unprovenanced`'s label explicitly states it cannot
be a regression baseline or contribute to a verified/Java-vs-Rust
conclusion).
