# Cache hierarchy — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (a misread measurement)

A colleague benchmarks summing a 64 MB `long[]` two ways: index order, and
"index order but starting from the middle" (`i` from N/2 to N, then 0 to
N/2). Both measure identically. They conclude: "position in RAM doesn't
matter, the cache-hierarchy story is overblown — I read half the array
'far away' first and paid nothing."

**Task:** explain why this experiment cannot distinguish the hierarchy's
levels at all, and design the *minimal* change that would.

**Success criteria:** you can state which property of the access pattern
(not the data's address) determines which level serves it; why both
variants have that property; and your redesigned experiment predicts a
concrete, directional time-per-element difference.

<details>
<summary>Hint</summary>

Both traversals are sequential from the prefetcher's point of view —
"where the array sits in RAM" was never the variable. What would make
consecutive accesses land on different cache lines, or different pages,
every time?

</details>

<details>
<summary>Solution</summary>

Both variants are two long sequential streams; hardware prefetchers stream
either one equally well, so nearly every access hits L1/L2 regardless of
the 64 MB footprint. Physical position in RAM is irrelevant to a warmed
stream; *predictability and locality* are the variables. The minimal
change: keep the same array and the same number of accesses, but visit
indices in a shuffled (random-permutation) order — now each access lands
on an unpredictable line, prefetching is defeated, and with a 64 MB
working set most accesses are served by DRAM. Prediction: time-per-element
rises by roughly an order of magnitude, and shrinking the same shuffled
traversal's working set back under the L2/L1 sizes collapses the gap —
which is exactly the lab benchmark's sweep.

</details>

## Exercise 2 — Implementation (make the working set fit)

The lab's benchmark sums a linked structure whose nodes are allocated
scattered across the heap (pointer-chasing). Your task: restructure the
*same logical list* so traversal becomes hierarchy-friendly without
changing what is computed.

**Task:** implement a layout change (Java or Rust project in `code/`) that
stores the nodes' payloads contiguously in traversal order (an array or a
flattened arena), keeping the same values and the same traversal result.

**Success criteria (measure, don't assert):**

1. The result of the traversal is bit-identical to the original.
2. At a working-set size that exceeds LLC, your contiguous version's
   time-per-element is measurably lower than the pointer-chasing version
   on your machine, and the gap *shrinks* when the working set fits in L1
   — record both numbers.
3. You can explain which of the two effects (spatial locality lowering
   misses per element, or prefetch-friendly addresses hiding latency) your
   numbers can and cannot separate.

<details>
<summary>Hint</summary>

You don't need to remove the indirection to win — you need consecutive
*dereferences* to touch consecutive lines. Sorting the nodes' storage by
traversal order (or storing payloads in a plain array indexed by position)
is sufficient; the list structure can even stay.

</details>
