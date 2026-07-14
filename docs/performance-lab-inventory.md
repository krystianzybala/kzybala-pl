# Performance Lab inventory (plab-001 baseline)

Snapshot of what actually exists on `main` before the curriculum manifest
lands, per `design.md`'s "Repository reality beats guessed filenames" rule.
Re-derive this from the code if it drifts — do not hand-edit it into fiction.

## Routes and content

Every route below is a real `lab/<id>/index.html` backed by a
`content/labs/<id>/` directory with `lab.json` + `theory.md` + `java.md` +
`rust.md` + `sources.md` (`docs/lab-metadata-schema.md` contract, enforced by
`scripts/validate-labs.js`). All seven are `"status": "stable"`.

| Route | Lab id | Level | Difficulty | `benchmark.md`? | Prerequisites |
|---|---|---|---|---|---|
| `/lab/cache-hierarchy/` | `cache-hierarchy` | 1 | beginner | yes | — |
| `/lab/false-sharing/` | `false-sharing` | 1 | intermediate | yes | — |
| `/lab/mesi/` | `mesi` | 1 | intermediate | no (conceptual only) | — |
| `/lab/memory-ordering/` | `memory-ordering` | 2 | advanced | no (conceptual only) | `mesi` |
| `/lab/cas-contention/` | `cas-contention` | 3 | advanced | yes | `memory-ordering` |
| `/lab/spsc-ring-buffer/` | `spsc-ring-buffer` | 4 | advanced | yes | `cas-contention` |
| `/lab/thread-per-core/` | `thread-per-core` | 5 | advanced | yes | `spsc-ring-buffer` |

This chain (cache-hierarchy/mesi → memory-ordering → cas-contention →
spsc-ring-buffer → thread-per-core) is the existing "reference spine" that
`assets/data/curriculum.json` builds on rather than replaces.

## Site infrastructure already in place

- **Metadata contract**: `content/labs/<id>/lab.json`, schema in
  `docs/lab-metadata-schema.md`.
- **Validation**: `scripts/validate-labs.js` (schema, duplicate ids,
  prerequisite existence, cycle detection) — runs in `npm run verify` and CI.
- **Build**: `scripts/generate-labs-index.js` writes
  `assets/data/labs-index.json`, the only lab metadata the deployed site
  fetches at runtime (`assets/js/core/metadata.js`).
- **Catalogue**: `assets/js/core/registry.js` renders/filters the grid on
  `/lab/` from `labs-index.json` (topic, difficulty, status filters).
- **Roadmap**: `assets/js/core/roadmap.js` renders the About-page timeline
  from `labs-index.json` status plus `assets/data/roadmap-planned.json` for
  ideas with no `lab.json` yet — deliberately small and unrelated to the
  36-lab curriculum manifest this change adds.
- **Interactive framework**: `assets/js/core/lab-framework.js` +
  `assets/js/core/components.js` (`plab-011-lab-framework`), consumed by
  each `assets/js/labs/<id>-lab.js`.
- **Consistency/regression gate**: `scripts/check-site-consistency.js` keeps
  stable labs reachable from the index, sitemap, and homepage.

## False Sharing, Ring Buffer, JIT Pipeline — behavior and gaps

`design.md` names these three as the labs to inventory and harden first.
Their actual state differs per lab:

- **False Sharing** (`false-sharing`) — **fully implemented reference lab**.
  Full `content/labs/false-sharing/` (theory, Java, Rust, measured
  benchmark with JMH/Criterion disclosure, sources), plus a small standalone
  interactive demo (`#false-sharing` tab on `/lab/`, driven by
  `assets/js/labs/false-sharing-reducer.js` via `prototype.js`) kept as a
  lightweight coherence animation separate from the full lab page. No gap
  for plab-001; `plab-010-false-sharing-reference-lab` is a hardening pass,
  not new content.
- **Ring Buffer** (`spsc-ring-buffer`) — **fully implemented reference lab**,
  same shape as False Sharing: full content directory plus a
  `#ring-buffer` prototype demo (`ring-buffer-reducer.js`). No gap for
  plab-001; `plab-013-spsc-ring-buffer-reference-lab` is a hardening pass.
- **JIT Pipeline** — **prototype only, no reference lab**. `/lab/` has a
  `#jit-pipeline` tab and an interactive tiered-compilation animation
  (`assets/js/labs/jit-pipeline-reducer.js`, mounted in `prototype.js`), but
  there is no `content/labs/jit-pipeline/` directory, no route at
  `/lab/jit-pipeline/`, no theory/Java/Rust content, and no benchmark. This
  is a real gap: the curriculum manifest marks `jit-pipeline` as
  `curriculumStatus: "partial"` (interactive demo exists, full lab does
  not), and `plab-012-jit-pipeline-reference-lab` is the change that closes
  it — plab-001 does not build that content itself (non-goal: "Implement
  all laboratory code in this change").

## The 36-lab curriculum and the 8-lab reference tier overlap

`openspec/changes/plab-{010,012,013,101-106,202-206,301-306,402,404,405,406,501-506,601-606}-*`
are exactly 36 change proposals, matching `design.md`'s "36 Java/Rust
laboratories." **Their own OpenSpec task lists are all at 0/N — none of
those 36 proposals has been applied as a change.** That is a true but easy
to misread statement: it describes the proposals, not the labs. Three of
the 36 proposals (`plab-010-false-sharing-reference-lab`,
`plab-013-spsc-ring-buffer-reference-lab`, `plab-012-jit-pipeline-reference-lab`)
target labs that already have real content or a real prototype, built
earlier under the pre-curriculum `plab-010`/`plab-024`/(none, for JIT)
numbering — see the previous section. So:

- **2 of the 36 curriculum labs are already `implemented`**
  (`false-sharing`, `spsc-ring-buffer`) — their proposal is a hardening
  pass on existing content, not a build-from-zero.
- **1 of the 36 is `partial`** (`jit-pipeline`) — the interactive demo
  exists, the reference lab does not.
- **33 of the 36 are `planned`** — genuinely nothing built yet.

`assets/data/curriculum.json` holds **41 unique laboratory entries, not
44** — the 36 curriculum labs and the 8 reference-tier labs **overlap by
3** (`false-sharing`, `spsc-ring-buffer`, `jit-pipeline` are simultaneously
reference-tier and curriculum members), so `36 + 8 − 3 = 41`. Every entry
carries an explicit `curriculumMember: boolean` field so this membership is
never inferred or re-derived incorrectly — see `docs/curriculum-manifest.md`
("Canonical terminology") for the full breakdown and the field-by-field
schema, and `scripts/test-curriculum.js` for the pinned aggregate-count
assertions that keep this document honest as the manifest grows.
