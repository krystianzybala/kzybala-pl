# Branch prediction and data distribution — exercises

## Exercise 1 (diagnosis): the "optimization" that only moved the bottleneck

A colleague benchmarks a filter-and-sum loop over 1,000,000 random
integers and reports: branchy = 2.9 ms, "optimized branchless" = 2.9 ms —
no improvement. Looking at their branchless code:

```java
long sum = 0;
for (int v : values) {
    boolean keep = v >= threshold;   // still a boolean...
    sum += keep ? v : 0;             // ...consumed by a ternary
}
```

Diagnose why this "branchless" version likely isn't branchless at all, and
state what evidence (not just the number) would confirm your diagnosis.

**Success criteria:** you name that a simple `cond ? a : b` ternary is a
prime candidate for the JIT/compiler lowering it to a conditional move
(`cmov`) *or* leaving it as a branch, depending on the optimizer's
heuristics — the source doesn't control which; you state that the
decisive evidence is `branches`/`branch-misses` from `perf stat` (or
`-prof perfasm`'s disassembly), not the wall-clock number alone, since an
unlucky cmov-vs-branch decision can make "branchless-looking" source
perform identically to the branchy baseline; and you point at this lab's
`filteredSumBranchless` (`(diff >>> 31) ^ 1` then multiply) as the
technique that removes the ambiguity by construction — no conditional or
ternary anywhere in the hot path for the optimizer to choose between.

<details>
<summary>Hint</summary>

Ask: if I disassembled this method, is there a `cmov`, a `jXX`, or
neither? A ternary over primitive types is exactly the shape where a
JIT's decision is least predictable from source alone.
</details>

## Exercise 2 (implementation): find the crossover

Using this lab's Java or Rust fixtures, add a fifth arrangement,
`biased_extreme`, generated the same way as `biased9010` but with a 99%
bias instead of 90%, and measure its filtered-sum time against
`branchless` on the byte-flags dataset (dev machine, wiring-only — do not
publish these numbers).

**Success criteria:** you correctly predict, before measuring, that a
99%-biased branch should be *faster* than the unconditional branchless
technique (near-perfect predictability makes the branch nearly free, while
branchless still pays its unconditional per-element cost on all
1,000,000 elements); you confirm or refute that prediction with your own
run and state which; and you identify the qualitative crossover point in
your own words — "branchless wins when the branch is unpredictable enough
that its expected misprediction cost exceeds branchless's guaranteed
per-element cost" — rather than citing a single number as a universal
threshold. Delete the added arrangement afterward; it is not part of the
lab's fixture contract.

<details>
<summary>Hint</summary>

You don't need new fixture values to reason about this qualitatively —
`biased9010`'s position between `random5050` and `sorted` in this lab's
theory page already tells you the direction; `biased_extreme` should sit
even closer to `sorted`.
</details>

<details>
<summary>Solution</summary>

At 99% bias, the predictor is wrong on roughly 1% of elements — a tiny
expected misprediction cost per element. The branchless technique, by
contrast, pays its fixed arithmetic cost (subtract, shift, xor, multiply,
add) on *every* element regardless of predictability. Once the branch's
*expected* cost (misprediction rate × penalty) drops below branchless's
*guaranteed* cost, the branchy version wins — which is exactly why this
lab never claims branchless is unconditionally faster, and why the
performance question is framed as "when," not "whether."
</details>

## Exercise 3 (evidence interpretation): read the counters, not just the clock

Below is the shape of one cell's `perf stat` output from this lab's
publication runner (illustrative structure, not real captured evidence):

```text
variant=random5050 dataset=byteFlags
      1,847,332,110      branches
        421,655,982      branch-misses           #   22.83% of all branches
      3,102,884,553      instructions
      2,290,117,004      cycles                   #    1.35  insn per cycle
```

Answer from the counters alone: (a) is a 22.83% branch-miss rate
consistent with this lab's `random5050` arrangement, given the theory
page's predictor model, and why or why not; (b) what would you expect
this same block to show for the `sorted` variant on the identical
dataset, qualitatively, on all three lines; (c) a colleague claims "IPC of
1.35 proves the branch predictor is the bottleneck" — name one alternative
explanation the IPC number alone cannot rule out.

**Success criteria:** (a) yes — a data-dependent branch that is
data-driven random with predicate-true share near 50% is close to the
theoretical worst case for a single saturating counter, so a miss rate in
the 20–30% range (not near 0% or near 50%) is plausible and worth
sanity-checking against the exact predicate-true share, not accepting
blindly; (b) `branches` and `instructions` stay close to the same order
(same loop, same element count), but `branch-misses` should collapse
toward a small fraction of a percent and IPC should rise, because sorting
does not change the amount of work, only its predictability; (c) IPC
depressed by 1.35 could equally come from memory-access stalls, a data
dependency chain, or front-end fetch-bandwidth limits unrelated to
branches — only the `branch-misses`-to-`branches` ratio, read alongside
IPC, actually implicates the predictor specifically.

<details>
<summary>Hint</summary>

IPC is an aggregate throughput number; it cannot by itself distinguish
"stalled waiting on a misprediction flush" from "stalled waiting on
memory" or "limited by a serial dependency chain." What other counter in
the same block speaks to branches specifically?
</details>
