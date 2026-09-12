# Bounds checks and loop shape — exercises

## Exercise 1 (diagnosis): the "safe unsafe" pull request

A pull request replaces a loop's plain array access with
`unsafe { *slice.get_unchecked(i) }` everywhere in a hot module, with the
commit message "removes bounds-check overhead, 5% faster." The loop being
changed is `for i in 0..slice.len() { sum += slice[i]; }`. Diagnose what
is almost certainly wrong with the justification, without assuming the
5% number is fabricated.

**Success criteria:** you identify that this exact loop shape (bound is
`slice.len()` directly, index is the loop counter) is precisely the
"canonical" shape LLVM's range-check elimination is designed to already
optimize — the check was very likely already eliminated before the
change; you name at least one alternative explanation for a real 5%
delta that has nothing to do with bounds checks (measurement noise,
inlining/codegen changes unrelated to the check itself, a different
compiler flag between the "before" and "after" builds); and you state
what evidence would actually settle it — the compiled assembly
(`cargo asm`) or LLVM optimization remarks for the *safe* version,
showing whether a check was present at all before the change.

<details>
<summary>Hint</summary>

This lab's own `canonical` and `unchecked` variants exist specifically to
be compared on identical data — what does their gap (or lack of one) on
real evidence, not assumption, usually look like for this exact loop
shape (theory.md's safety/performance matrix)?
</details>

## Exercise 2 (implementation): build the opaque-limit barrier yourself

Using this lab's Java or Rust code, write a **new** loop bound provider
that is more subtle than this lab's `opaqueLength`/`opaque_len`: instead
of a `DONT_INLINE`/`#[inline(never)]` method, pass the array length
through a `synchronized` field read (Java) or an `AtomicUsize::load`
(Rust) instead. Predict, before measuring, whether this alternative
barrier defeats range-check elimination as reliably as the lab's own
approach, and state your reasoning.

**Success criteria:** you state a specific prediction (e.g., "a volatile/
atomic read is *also* opaque to escape analysis because the compiler
cannot assume its value without re-reading it, so it should behave
similarly to `DONT_INLINE`" or the opposite, with reasoning either way);
you verify it with a dev-only run (never published) comparing the new
variant's ns/element against this lab's existing `canonical` and
`opaqueLimit`; and you delete the experimental variant afterward — it is
not part of the lab's fixture contract.

<details>
<summary>Hint</summary>

Both `DONT_INLINE`/`#[inline(never)]` and a volatile/atomic read share
one property that matters here: the compiler cannot assume a *specific*
constant value crossed the boundary. Is that property what actually
defeats RCE, or is it something else about the call itself?
</details>

<details>
<summary>Solution</summary>

Both approaches typically defeat RCE for the same underlying reason: the
optimizer loses the ability to statically prove the bound's relationship
to the array's real length, whether that's because the value crossed an
un-inlined call boundary or because it came from a location (`volatile`/
`Atomic*`) the compiler must treat as potentially changing between reads.
The specific mechanism differs, but the *effect* — the compiler falling
back to a per-iteration check — is the shared cause. If your measurement
shows a materially different result, the more likely explanation is that
one barrier was successfully hoisted/constant-folded by a smarter
optimizer pass than the other, not that "opaque" barriers are
unpredictable in general.
</details>

## Exercise 3 (evidence interpretation): read the branch counter, not the label

Below is the shape of two cells' `perf stat` output from this lab's
publication runner (illustrative structure, not real captured evidence):

```text
variant=canonical    dataset=primitiveArrays   branches: 1,003,881   branch-misses: 12,004  (1.2%)
variant=opaqueLimit  dataset=primitiveArrays   branches: 2,001,442   branch-misses: 18,221  (0.9%)
```

Answer from these two lines alone: (a) does roughly 2× the `branches`
count for `opaqueLimit` versus `canonical`, on the identical 1,000,000-
element loop, support the theory-page claim that the opaque variant pays
a per-iteration check the canonical variant does not; (b) the
`branch-misses` *percentage* is actually slightly lower for `opaqueLimit`
— does that undermine the conclusion in (a); (c) name one metric, not
shown here, that would let you separate "the extra branches are bounds
checks" from "the extra branches are something else entirely
(loop-unrolling artifacts, a different codegen shape)."

**Success criteria:** (a) yes — a canonical stride-1 loop over N elements
with the check eliminated executes close to N loop-control branches; an
un-eliminated per-element check roughly doubles that to ~2N, which is
exactly the ~2× ratio shown; (b) no — miss *rate* and branch *count* are
independent questions: the extra bounds-check branches are highly
predictable (they almost never fire, since the data never goes out of
range), so a LOW miss rate on a HIGHER branch count is exactly what a
successfully-*retained-but-predictable* check looks like, not evidence
against the extra-branches explanation; (c) the actual disassembly
(`-prof perfasm` / `cargo asm`) for the two variants — only looking at
the generated instructions can confirm the extra branches are bounds
checks specifically, rather than inferring it from aggregate counters
alone.

<details>
<summary>Hint</summary>

A predictable branch and an eliminated branch have very different counter
signatures even though both "cost little" in the sense of misprediction —
one still executes, the other never exists. Which counter distinguishes
"executes but predicts well" from "doesn't exist"?
</details>
