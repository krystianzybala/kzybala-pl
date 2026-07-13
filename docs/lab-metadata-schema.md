# Lab metadata contract (`lab.json`)

Every lab directory under `content/labs/<lab-id>/` MUST contain a `lab.json`
that validates against this contract. It is enforced by
`scripts/validate-labs.js` in CI — see `docs/new-lab-workflow.md`.

Directories starting with `_` (e.g. `_template`) are excluded from validation.

## Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | Kebab-case (`^[a-z0-9]+(-[a-z0-9]+)*$`). Must equal the parent directory name. Must be unique across all labs. |
| `title` | string | yes | Non-empty. |
| `status` | string | yes | One of `draft`, `stable`, `deprecated`. |
| `level` | integer | yes | `>= 1`. Position in the learning graph, not a difficulty score. |
| `difficulty` | string | yes | One of `beginner`, `intermediate`, `advanced`. |
| `durationMinutes` | integer | yes | `> 0`. Estimated time to complete. |
| `topics` | string[] | yes | At least one entry. |
| `prerequisites` | string[] | yes | Lab `id`s that must be completed first. May be empty. Must not include its own `id`. |
| `unlocks` | string[] | yes | Lab `id`s this lab unlocks. May be empty. For every `unlocks` entry, the target lab's `prerequisites` must list this lab back (bidirectional consistency). |
| `languages` | string[] | yes | Subset of `java`, `rust`. May be empty for non-code labs. |
| `interactive` | boolean | yes | Whether the lab has an interactive visualisation. |
| `benchmark` | boolean | yes | Whether the lab includes benchmark data. If `true`, `benchmark.md` is required and the benchmark disclosure component (`docs/components.md`) must be used. |
| `conceptualModel` | boolean | yes | Whether the interactive visualisation is a simplified conceptual model rather than a measured simulation. |

## Validation rules (enforced in CI)

1. **Metadata schema** — every field above is present with the correct type and, where applicable, a valid enum value.
2. **Duplicate IDs** — no two labs may share an `id`.
3. **Prerequisites** — every `id` referenced in `prerequisites` or `unlocks` must exist as a real lab, and `unlocks`/`prerequisites` must be mutually consistent.
4. **Cycle detection** — the `prerequisites` graph must be a DAG. A lab can never (transitively) depend on itself.

## Example

```json
{
  "id": "false-sharing",
  "title": "False Sharing",
  "status": "stable",
  "level": 2,
  "difficulty": "intermediate",
  "durationMinutes": 20,
  "topics": ["cpu-cache", "coherence", "concurrency"],
  "prerequisites": ["cache-lines"],
  "unlocks": ["mesi", "cache-aware-layout"],
  "languages": ["java", "rust"],
  "interactive": true,
  "benchmark": true,
  "conceptualModel": true
}
```
