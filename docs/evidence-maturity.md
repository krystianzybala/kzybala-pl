# Evidence-maturity workflow (plab-003 task 7)

`scripts/benchmark-platform/results/evidence-maturity.js` implements
design.md's "Review workflow": "A run moves draft → reproduced → profiled →
verified. Site badges reflect actual state and may regress if provenance is
invalidated."

## States

| State | Meaning | Requires raw artifact? |
|---|---|---|
| `draft` | Ran once, informally. | No. |
| `reproduced` | Raw artifact preserved, re-run at least once with a compatible result. | Yes. |
| `profiled` | Reproduced, plus profiler/perf-counter evidence attached. | Yes. |
| `verified` | Reviewed and accepted as the current publication-grade number. | Yes. |
| `legacy-unprovenanced` | Pre-plab-003 hand-authored number, no raw artifact ever captured. | No — and never will have one retroactively. |

`legacy-unprovenanced` is not a stage in the forward progression — it has no
outgoing transitions (`canTransition("legacy-unprovenanced", anything)` is
always `false`). It can only be *replaced* by a brand-new `draft` record
that starts the real workflow from scratch (see
`docs/benchmark-results-migration.md`), never promoted in place — there is
no amount of documentation that turns a number with no raw artifact into a
"reproduced" one.

## Transitions

`canTransition(from, to)` / `transition(current, next)` (throws on an
illegal move):

```
draft       -> reproduced, profiled
reproduced  -> profiled, verified, draft
profiled    -> reproduced, verified, draft
verified    -> draft
```

`profiled` and `reproduced` are siblings a run can reach in either order —
a profiler capture doesn't strictly require an independent second run
first. Nothing skips directly to `verified` without passing through at
least one of them. Anything may regress to `draft` if its provenance is
later invalidated (design.md).

## Badges

`badgeFor(maturity)` returns `{ label, isPublishable }` — `draft` is the
only forward-progression state marked `isPublishable: false` ("not for a
benchmark.md table"); everything else, including `legacy-unprovenanced`, may
appear on a page as long as its badge is honest about what it is.
