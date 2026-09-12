# Array of Structures vs Structure of Arrays — exercises

## Exercise 1 (diagnosis): the SoA migration that got slower

A team migrates their fifteen-field position record from AoS to full SoA
— fifteen separate arrays — expecting their risk engine (reads only
`quantity` and `averagePrice`) to speed up. It does. But their nightly
reconciliation job, which reads *every* field of *every* position to
produce an audit report, got measurably **slower** after the same
migration. Diagnose why, using this lab's mechanism, and name the layout
that would have given the risk engine most of its win without hurting the
reconciliation job.

**Success criteria:** you explain that full SoA turns a whole-record read
into fifteen independent, non-contiguous fetches — one per field array —
where AoS needed exactly one cache-line-friendly access per record; you
name that the reconciliation job's access pattern (all fields, together)
is the opposite of the risk engine's (two fields, together), so the "same"
migration helps one and hurts the other by the identical mechanism; and
you name the **hybrid hot/cold split** — group `quantity`+`averagePrice`
into their own dense pair, leave the other thirteen fields as an AoS
block — as the layout that serves both access patterns without forcing a
language-level trade-off between them.

<details>
<summary>Hint</summary>

Ask, for each consumer separately: how many of the record's fields does
it read, and are they read together or independently? The layout that
wins is the one that matches *that* consumer's pattern — theory.md's
"throughput by accessed-field ratio" table has both rows.
</details>

## Exercise 2 (implementation): measure your own density claim

Using this lab's fixtures, add a fifth dataset shape — hot bytes = 16,
cold bytes = 256 (16 cold `long` fields) — and predict, before running
anything, the AoS-packed useful-byte ratio and the approximate number of
64-byte cache lines a 1,000-record hot-field-only scan should touch under
AoS-packed vs SoA. Then implement it (reuse `AosVsSoaFixtures.generate`
with `coldWords=32`... note: `cold` is `long[]`, so 16 cold *fields* means
`coldWords=16`) and verify your prediction against JMH or Criterion on
your own machine (dev wiring only — do not publish these numbers).

**Success criteria:** your predicted useful-byte ratio matches
`16/(16+128) ≈ 0.111` (16 hot bytes out of a 144-byte AoS-packed stride);
your predicted cache-line counts follow this lab's Visualization 2
arithmetic; your measured ns/record ordering (SoA/hybrid fastest,
AoS-packed and AoS-heap slower) matches the direction you predicted, even
if the exact numbers do not (a Mac or non-reference-host run is
illustrative only); and you delete the added dataset afterward — it is
not part of the lab's fixture contract.

<details>
<summary>Hint</summary>

Density arithmetic doesn't need a benchmark to compute — it's
`hotBytes / (hotBytes + coldBytes)`. Predict it on paper first, then let
the benchmark either confirm the *direction* or surface something your
model missed (prefetcher behavior, alignment, cache associativity).
</details>

<details>
<summary>Solution</summary>

At 16 cold `long` fields (128 cold bytes) against 16 hot bytes, the
AoS-packed stride is 144 bytes/record and the useful-byte ratio is
16/144 ≈ 0.111 — worse than every dataset already in this lab
(spatialPoints 0.50, marketQuotes 0.33, positions 0.20), so the
AoS-vs-SoA gap should widen further, not narrow, as cold payload grows.
If your measurement shows the gap *shrinking* instead, suspect the
benchmark is not actually excluding cold bytes from any code path (a
"changing semantics across layouts" trap — verify the hot loop truly
never touches `cold[]`).
</details>

## Exercise 3 (evidence interpretation): read the layout, not just the number

Below is the shape of two cells' declared-vs-measured output from this
lab's publication runner (illustrative structure, not real captured
evidence):

```text
variant=aosPacked dataset=positions   recordStrideBytes=80  cache-misses=41,204,118
variant=soa       dataset=positions   recordStrideBytes=n/a cache-misses=8,912,344
```

Answer from these two lines alone: (a) is a ~4.6× cache-miss ratio
between `aosPacked` and `soa` broadly consistent with this lab's
Visualization 2 arithmetic for the `positions` dataset, and why or why
not; (b) `soa`'s row has no `recordStrideBytes` — is that a missing-data
bug or expected, given what `recordStrideBytes` means for a layout with
no single per-record stride; (c) a colleague claims "this proves SoA is
always better for positions" — name the one detail about *this specific
operation* that limits how far that conclusion can be generalized.

**Success criteria:** (a) yes, directionally — Visualization 2's model
predicts AoS-packed fetches roughly 5× the cache lines SoA does for the
`positions` dataset (80 vs 16 useful bytes per 80-byte-line-equivalent),
so a cache-miss ratio in that neighborhood is plausible, though the exact
multiplier depends on prefetcher behavior the arithmetic doesn't model;
(b) expected, not a bug — `recordStrideBytes` is a property of a
fixed-stride struct layout, and SoA has no single record stride by
construction (its "stride" is field-array-relative, already captured by
`totalBytes`/element count instead); (c) the operation reads only two
fields together — the conclusion is scoped to hot-field-only scans of
*this* record shape, not to whole-record operations, which this lab's
theory page and Exercise 1 both show can reverse the ranking entirely.

<details>
<summary>Hint</summary>

`recordStrideBytes` only makes sense for a layout where "one record" is a
single, fixed-size, contiguous unit. Which of this lab's four layouts
have that property, and which don't — and why does that map exactly to
which ones report a stride?
</details>
