# Heap vs off-heap with FFM MemorySegment — exercises

## Exercise 1 (diagnosis): the migration that made things worse

A team migrates a hot read path from a `long[]` array to a
`MemorySegment` (`Arena.ofShared()`), expecting better throughput because
"off-heap avoids GC pauses." After deploying, p50 latency is roughly
unchanged but CPU usage on the hot path rises noticeably. Using this
lab's mechanism, name the most likely explanation, and state which of
this lab's own measured variants demonstrates it directly.

**Success criteria:** you identify that removing GC involvement does not
imply removing per-access cost — `MemorySegment.get` calls carry their
own access-mode and bounds checks that are not automatically cheaper
than a plain array read, exactly this lab's `heapPrimitiveArray`-beats-
`sharedSegment` finding on `sequentialSum` (java.md); you state that if
the workload was never GC-pause-bound in the first place (no evidence
was gathered on GC pause frequency BEFORE migrating), the team paid a
real per-access cost for a benefit that may not have applied to their
workload; and you name the concrete next step — measure GC pause
frequency and duration on the ORIGINAL `long[]` version before assuming
off-heap was the right lever at all.

<details>
<summary>Hint</summary>

This lab's hypothesis is stated as a two-part claim: off-heap provides
explicit control, BUT various costs can erase gains. Which half of that
claim did the team's migration plan account for?
</details>

## Exercise 2 (implementation): measure the crossing point

Using this lab's Java `FixedRecordsStorage.copiedBoundaryCrossing` and
`slicedView`, add a benchmark parameter that varies K (the number of
`randomAccess` indices) across `{100, 1000, 10000, 100000, 500000}` on
`fixedRecords` (dev machine, wiring-only — do not publish these
numbers). Predict, before measuring, the approximate K at which
`copiedBoundaryCrossing`'s `randomAccess` cost drops BELOW
`slicedView`'s — i.e., the point where copying everything upfront
becomes cheaper than K individual off-heap reads.

**Success criteria:** you implement the varying-K benchmark correctly
(checksum must still match the fixture-pinned random-access oracle for
each K); you state a specific, falsifiable prediction for the
crossover K, reasoned from this lab's own measured per-unit costs (e.g.,
"if the whole-dataset copy costs roughly N × (per-record copy cost) and
each `slicedView` random read costs roughly `sliced_view`'s measured
ns/access, the crossover is where K × ns/access(sliced) ≈
N × ns/record(copy)"); you measure and report the actual crossover; and
you state whether it landed close to your prediction or not, and why.

<details>
<summary>Hint</summary>

java.md's dev numbers give you `slicedView`'s ns/access
(≈2.05) and enough information to estimate `copiedBoundaryCrossing`'s
fixed whole-copy cost independent of K. The crossover is where the two
cost curves intersect — set up that equation before running anything.
</details>

<details>
<summary>Solution</summary>

There is no universally correct crossover independent of the measured
host, but the mechanism predicts it should land somewhere well below
N (500,000) — specifically near
`(copy cost) / (ns/access(sliced))`, which on this lab's own dev numbers
works out to a small fraction of N (a few thousand, not hundreds of
thousands), since the whole-dataset copy cost is large but fixed while
each individual sliced read is cheap. If your measured crossover lands
far from this estimate, the more likely explanation is that the copy's
own per-record cost differs from the simple per-record model (e.g., bulk
copy is not simply N times a single-record copy due to memory bandwidth
effects) — worth checking explicitly with a fixed-K, varying-N run
before concluding the mechanism itself is wrong.
</details>

## Exercise 3 (evidence interpretation): read the arena kind, not just the number

Below is the shape of one run's JMH output for `fixedRecords`
(illustrative structure, not real captured evidence):

```text
Benchmark                    (variant)  Mode  Cnt      Score   Error  Units
...sequentialSum       confinedSegment  avgt      810000          ns/op
...sequentialSum         sharedSegment  avgt      815000          ns/op
...randomAccess        confinedSegment  avgt       26000          ns/op
...randomAccess          sharedSegment  avgt       58000          ns/op
```

Answer from this block alone: (a) `sequentialSum`'s numbers for the two
arena kinds are nearly identical (810000 vs. 815000) while
`randomAccess`'s differ by more than 2× (26000 vs. 58000) — what does
this pattern suggest about WHERE a shared arena's extra cost actually
shows up; (b) is it valid to conclude "shared arenas are always ~2×
slower than confined ones" from this data alone; (c) name one plausible,
mundane reason `randomAccess` specifically (rather than
`sequentialSum`) might be more sensitive to arena-kind overhead.

**Success criteria:** (a) the pattern suggests shared-arena overhead is
closer to a fixed per-access cost (visible per-call in `randomAccess`'s
smaller unit of work) than a cost proportional to total bytes processed
(which would show up comparably in `sequentialSum`'s much larger unit of
work too) — a real, useful distinction this lab's two-operation split is
designed to expose; (b) no — this is ONE dataset, one host, one JDK
build; this lab's own limitations section states no result should be
generalized as a fixed multiplier without re-measurement on the target
host; (c) a plausible, mundane reason is that `sequentialSum` is
dominated by raw memory bandwidth for a large linear scan, which can
mask a small per-access overhead, while `randomAccess`'s smaller,
scattered unit of work has less bandwidth-bound cost to hide behind —
worth stating as a hypothesis to verify, not a proven conclusion, from
this block alone.

<details>
<summary>Hint</summary>

This lab's theory page states explicitly that shared-arena bookkeeping
is a per-access cost. Which of the two benchmark methods does more total
work per individual access, and would that dilute or concentrate a
per-access overhead in the reported ns/op?
</details>
