# Curriculum manifest contract (`curriculum.json`)

`assets/data/curriculum.json` is the single source of truth for the full
Performance Lab curriculum — every lab that is implemented, partially
implemented, or merely planned. It is deliberately separate from
`content/labs/<id>/lab.json` (`docs/lab-metadata-schema.md`):

- `content/labs/*/lab.json` describes labs that **already have content** —
  every field there must be backed by real theory/Java/Rust/benchmark files.
- `curriculum.json` describes **the whole curriculum**, including labs that
  don't exist yet. A lab can appear here with `curriculumStatus: "planned"`
  and no `content/labs/` directory at all — that is the normal, expected
  state for most entries, not a bug.

Validated by `scripts/validate-curriculum.js`, run in `npm run verify` and CI.
Aggregate counts below are pinned by `scripts/test-curriculum.js`'s
`CANONICAL_COUNTS` block — if you add, remove, or reclassify an entry, that
test fails until this section is updated to match, and vice versa.

## Canonical terminology

Two independent classifications apply to every entry, and they **overlap**
— read "the 36-lab curriculum" and "the 8-lab reference tier" as two
different lenses on the same 41-entry manifest, not two separate lists that
sum to 44:

- **Unique laboratory** — any single entry in `curriculum.json`, identified
  by its `id`. **41 total.** This is the only number that counts "how many
  labs exist" without double-counting.
- **Reference-tier laboratory** — `category === "reference"`: the
  pre-existing cache/coherence/concurrency spine (cache-hierarchy → mesi →
  memory-ordering → cas-contention → spsc-ring-buffer → thread-per-core)
  plus JIT Pipeline. **8 total.**
- **Curriculum laboratory** (a.k.a. "curriculum member") —
  `curriculumMember === true`: tracked by one of the 36
  `plab-{010,012,013,101-106,202-206,301-306,402,404,405,406,501-506,601-606}`
  curriculum proposals (`docs/performance-lab-inventory.md`). **36 total.**
  This field is explicit data, not inferred from `category` or from
  `plannedChange` being non-null — `plannedChange` can legitimately become
  `null` later (once a queued change lands and nothing further is queued)
  without the lab stopping being a curriculum member.
- **Reference-only laboratory** — reference-tier but not a curriculum
  member: `cache-hierarchy`, `mesi`, `memory-ordering`, `cas-contention`,
  `thread-per-core`. Built under the earlier `plab-020`/`plab-021-025`
  numbering, before the 36-lab curriculum proposals existed; no curriculum
  proposal currently targets them. **5 total.**
- **Overlap laboratory** — both reference-tier and a curriculum member:
  `false-sharing`, `spsc-ring-buffer`, `jit-pipeline`. These are the three
  labs `design.md` calls out by name ("Treat the existing False Sharing,
  Ring Buffer and JIT Pipeline pages as reference labs to inventory and
  harden") — they anchor the reference tier *and* are explicitly tracked by
  a curriculum proposal (`plab-010`, `plab-013`, `plab-012` respectively).
  **3 total.**
- **Curriculum-only laboratory** — a curriculum member that is not
  reference-tier, i.e. `category !== "reference"`. **33 total.**
- **Planned laboratory** — `curriculumStatus === "planned"`. **33 total**
  (every curriculum-only entry; no reference-tier entry is ever planned in
  the current manifest).
- **Partial laboratory** — `curriculumStatus === "partial"`. **1 total**
  (`jit-pipeline`).
- **Implemented laboratory** — `curriculumStatus === "implemented"`.
  **7 total** (the reference-only 5 plus `false-sharing` and
  `spsc-ring-buffer` from the overlap).
- **Verified laboratory** — `curriculumStatus === "verified"`. **0 total**
  — no entry has reached this tier yet (`plab-003`'s job).

The identity that must always hold, and that `scripts/test-curriculum.js`
asserts directly against `curriculum.json`:

```
reference-only (5) + overlap (3) + curriculum-only (33) = unique laboratories (41)
reference-only (5) + overlap (3)                        = reference-tier (8)
overlap (3)         + curriculum-only (33)               = curriculum members (36)
planned (33) + partial (1) + implemented (7) + verified (0) = unique laboratories (41)
```

## Fields

| Field | Type | Notes |
|---|---|---|
| `id` | string | Kebab-case, unique. Equals the `content/labs/<id>` directory name once the lab is implemented. |
| `title` | string | Non-empty. |
| `category` | string | One of the keys in `assets/js/core/curriculum.js`'s `CATEGORIES`. |
| `curriculumMember` | boolean | `true` if this lab is tracked by one of the 36 curriculum proposals (see "Canonical terminology" above). Every entry with a non-`reference` category must be `true` — enforced by `scripts/validate-curriculum.js`. |
| `path` | string | One of `foundational`, `intermediate`, `advanced`, `capstone` (`assets/js/core/curriculum.js`'s `LEARNING_PATHS`). |
| `level` | integer | `>= 1`. Position within the lab's own category/path — not a global ranking. |
| `focusQuestion` | string | The single question the lab answers. Drives the card copy; never a marketing tagline. |
| `curriculumStatus` | string | One of `planned`, `partial`, `implemented`, `verified` — see below. |
| `evidenceMaturity` | string \| null | One of `draft`, `reproduced`, `profiled`, `verified`, or `null` if the lab has no benchmark result yet. |
| `languages` | string[] | Subset of `java`, `rust`. |
| `durationMinutes` | integer | `> 0`. For `planned`/`partial` entries this is an authoring estimate, not a measurement — never present it as one. |
| `prerequisites` | string[] | `id`s that should be completed first. May be empty. Must not include its own `id`. |
| `route` | string \| null | `/lab/<id>/` once the lab is live, otherwise `null`. |
| `sourceChange` | string \| null | The OpenSpec change that built the current content, or `null` if nothing has been built yet. |
| `plannedChange` | string \| null | The OpenSpec change that will move this entry forward (initial build or a hardening pass), or `null` if none is queued. |

## `curriculumStatus` values

Distinguishes what spec.md's "Curriculum manifest" requirement calls
implemented, partial, planned and verified laboratories:

- **`planned`** — no `content/labs/` directory, no route. The default for
  a lab that exists only as an OpenSpec proposal.
- **`partial`** — some real artifact exists (e.g. an interactive prototype
  demo) but the lab is missing required content (theory, one of the
  languages, or the benchmark) and has no `/lab/<id>/` route of its own.
- **`implemented`** — full `content/labs/<id>/` directory, live route,
  passes `scripts/validate-labs.js`. Evidence may still be single-run.
- **`verified`** — `implemented`, plus the evidence has been reproduced
  across runs/hosts per the provenance pipeline (`plab-003`). No entry in
  this manifest is `verified` yet — that status is earned, never assigned
  up front.

**No synthetic completion**: `curriculumStatus` may only be `implemented` or
`verified` if `route` is non-null and `lab/<id>/index.html` actually exists.
`scripts/validate-curriculum.js` enforces this automatically.

## Route compatibility

None of the 7 existing routes (`/lab/cache-hierarchy/`, `/lab/false-sharing/`,
`/lab/mesi/`, `/lab/memory-ordering/`, `/lab/cas-contention/`,
`/lab/spsc-ring-buffer/`, `/lab/thread-per-core/`) change as part of this
manifest — see `docs/performance-lab-inventory.md`. `scripts/validate-curriculum.js`
enforces the link between `route` and reality in both directions: an
`implemented`/`verified` entry's route must resolve to a real
`lab/<id>/index.html`, and a `planned`/`partial` entry must not claim a
route at all. No redirect is needed anywhere in this change because no path
is moving; a future hardening pass (e.g. `plab-010-false-sharing-reference-lab`)
that changes a route would need to add one and update this manifest's
`route` field in the same change.

## Examples

The overlap and reference-only examples below are excerpts (fields omitted
for brevity) — see `assets/data/curriculum.json` for the real, complete
entries.

A curriculum-only entry (`category !== "reference"`, so `curriculumMember`
must be `true`):

```json
{
  "id": "branch-prediction",
  "title": "Branch Prediction and Data Distribution",
  "category": "foundations",
  "curriculumMember": true,
  "path": "intermediate",
  "level": 3,
  "focusQuestion": "When does a simple conditional become more expensive than the work guarded by it?",
  "curriculumStatus": "planned",
  "evidenceMaturity": null,
  "languages": ["java", "rust"],
  "durationMinutes": 25,
  "prerequisites": ["clocks-latency-histograms"],
  "route": null,
  "sourceChange": null,
  "plannedChange": "plab-103-branch-prediction"
}
```

An overlap entry — `category: "reference"` **and** `curriculumMember: true`,
because `plab-013-spsc-ring-buffer-reference-lab` is one of the 36
curriculum proposals:

```json
{
  "id": "spsc-ring-buffer",
  "title": "SPSC Ring Buffer",
  "category": "reference",
  "curriculumMember": true,
  "path": "advanced",
  "level": 4,
  "curriculumStatus": "implemented",
  "sourceChange": "plab-024-spsc-ring-buffer",
  "plannedChange": "plab-013-spsc-ring-buffer-reference-lab"
}
```

A reference-only entry — `category: "reference"` **and**
`curriculumMember: false`, because no curriculum proposal targets it:

```json
{
  "id": "mesi",
  "title": "Cache Coherence and MESI",
  "category": "reference",
  "curriculumMember": false,
  "curriculumStatus": "implemented",
  "sourceChange": "plab-021-cache-coherence-mesi",
  "plannedChange": null
}
```
