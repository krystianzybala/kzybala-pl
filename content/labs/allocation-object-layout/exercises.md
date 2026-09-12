# Allocation cost and object layout — exercises

## Exercise 1 (diagnosis): the migration that didn't shrink the heap

A team migrates a hot path from `List<Point>` (a boxed object graph, one
`Point` per element) to two parallel `int[]` arrays (`xs`, `ys`),
expecting a large heap reduction. After deploying, heap usage drops, but
by noticeably less than the naive "8 logical bytes × record count"
estimate predicted. Using this lab's mechanism, name the two things the
naive estimate most likely omitted, and which of this lab's own datasets
demonstrates each one directly.

**Success criteria:** you name (a) the object header and alignment
padding that the ORIGINAL boxed representation was paying per instance,
on top of the 8 logical bytes — demonstrated directly by this lab's
`smallTuples` dataset's measured header-overhead ratio (java.md); and
(b) that the two arrays' own overhead (two array headers, plus any
padding for the array lengths) is small but nonzero and should be
subtracted from the naive savings estimate — demonstrated by
`flatPrimitiveArrays`' own measured bytes/record being slightly above
the pure logical-field total. You conclude the actual savings were real
and substantial, just smaller than "8 bytes × N" because the naive
estimate implicitly assumed the ORIGINAL representation had zero
overhead, which this lab's evidence shows is never true for boxed
objects.

<details>
<summary>Hint</summary>

This lab's theory page states the header-overhead ratio explicitly for
three record sizes. Which of them is closest to a two-`int` `Point`, and
what ratio did it show?
</details>

## Exercise 2 (implementation): find where the packed struct wins

Using this lab's Java `PackedOffHeapStruct` and `BoxedObjectGraph`, add a
`sumHotRepeated(int passes)` variant that calls `sumHot()` (or the
packed equivalent) `passes` times over the SAME already-built
representation and returns the final sum. Benchmark both representations
at `passes = 1, 10, 100` on `ordersQuotes` (dev machine, wiring-only — do
not publish these numbers). Predict, before measuring, whether the
packed struct's disadvantage from java.md (slower `sumHot` on this dev
run) holds at every pass count, or whether repeated reads eventually
favor the denser representation.

**Success criteria:** you implement the variant correctly (checksum must
still be `passes` times the single-pass checksum, wrapping); you state a
specific, falsifiable prediction (e.g., "the packed struct's
disadvantage is per-access overhead, so it should scale linearly with
`passes` and never catch up" — or the opposite, with reasoning); you
measure and report the actual result; and you connect it to this lab's
mechanism — `MemorySegment` access overhead is a per-call cost, not a
warm-up cost, so it is expected to persist (and dominate proportionally
more, not less) as `passes` grows, unless cache effects from the boxed
graph's larger footprint start to dominate at very large `passes` (worth
checking explicitly, not assuming).

<details>
<summary>Hint</summary>

java.md's dev finding was for exactly ONE pass. Nothing about that
finding claims it holds at 10 or 100 passes — that is what this exercise
asks you to actually check.
</details>

<details>
<summary>Solution</summary>

There is no universally correct answer independent of the measured host
and JDK build, but the mechanism predicts the packed struct's per-access
overhead should persist proportionally across repeated passes, since
`MemorySegment.get`'s cost is paid on every call, not once. If your
measurement shows the packed struct closing the gap at high pass counts,
the more likely explanation is the boxed graph's larger footprint
starting to exceed a cache level that the packed struct's denser layout
still fits inside — check the `bytesPerRecord` numbers from `JolReport`
against your host's cache sizes before concluding the per-access
overhead itself changed; that would be a locality effect layered on top
of, not a contradiction of, this lab's mechanism.
</details>

## Exercise 3 (evidence interpretation): read the JOL report, not the headline number

Below is the shape of one run's JOL output for a hypothetical fourth
dataset in this lab's family (illustrative structure, not real captured
evidence):

```text
=== wideRecord: single-instance layout ===
Instance size: 56 bytes
=== wideRecord: whole-graph footprint for 1000 records ===
     COUNT       AVG       SUM   DESCRIPTION
         1      4016      4016   [L...WideRecord;
      1000        56     56000   ...WideRecord
      1001               60016   (total)
boxedObjectGraph: totalBytes=60016 bytesPerRecord=60.016
packedOffHeapStruct: bytesPerRecord=48 (no header, no reference indirection)
headerOverheadRatio (boxed / packed) = 1.25x
```

Answer from this block alone: (a) is a 1.25× ratio consistent with this
lab's mechanism, given `wideRecord`'s 48-byte packed size is much larger
than any of this lab's three real datasets; (b) the array header
(4016 bytes for 1000 references) is a FIXED cost independent of record
count — at what record count would this array header's contribution to
`bytesPerRecord` become negligible (say, under 1% of the boxed total);
(c) name one reason `wideRecord`'s ratio being close to 1.25× does NOT
mean packing off-heap is "not worth it" for this record shape.

**Success criteria:** (a) yes — this lab's theory page states the ratio
gets worse as records get SMALLER, so a large 48-byte record showing a
comparatively mild 1.25× ratio is exactly consistent with, not a
contradiction of, the mechanism; (b) roughly 1000 records's headline
math (4016 bytes ÷ 1001 records ≈ 4 bytes/record already, at N=1000) —
for the array header to drop under 1% of a ~60-byte-per-record boxed
total, you need roughly N > 6,700 records (4016 / 6700 ≈ 0.6 bytes,
under 1% of 60), so this specific illustrative run's N=1000 sample is
already close to that threshold, worth naming explicitly rather than
assuming footprint ratios are N-independent; (c) even a modest 1.25×
footprint reduction can matter enormously at scale (a service holding
tens of millions of such records), and — per this lab's own
`packedOffHeapStruct` read-cost finding (java.md) — footprint reduction
and access-speed change are separate questions; a small footprint win is
still a real win worth having even when it does not also produce a
faster read, as long as that trade-off is stated explicitly rather than
assumed away.

<details>
<summary>Hint</summary>

This lab's own three real datasets show the ratio worsening as records
shrink (java.md's 1.83×/3.50×/4.00× progression at 24/8/8 packed bytes).
Where would a 48-byte record's ratio be expected to fall on that curve?
</details>
