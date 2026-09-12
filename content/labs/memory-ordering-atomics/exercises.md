# Memory Ordering: VarHandles and Rust Atomics — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the counter that quietly loses updates)

A colleague "fixes" a lost-updates bug report by switching a shared
counter field from a plain `int` to `volatile int` (Java) or wrapping it
in an `AtomicI32` and reading/writing it with `Ordering::SeqCst` (Rust),
without changing anything else about how it's updated:

```java
// Java
volatile int value;
// four threads each run:
for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
    value = value + 1;
}
```

```rust
// Rust
let value = AtomicI32::new(0);
// four threads each run:
for _ in 0..INCREMENTS_PER_THREAD {
    let cur = value.load(Ordering::SeqCst);
    value.store(cur + 1, Ordering::SeqCst);
}
```

They report back that "the numbers still don't match, and now it's
*volatile*/*SeqCst*, so I don't understand how it can still be wrong."

**Task:** explain, precisely, why upgrading to the strongest built-in
ordering did not fix the bug, and name the two categories of fix that
would.

**Success criteria:** your explanation names the specific gap (ordering
strength vs. atomicity of the *compound* operation), and both named fixes
are genuinely different mechanisms from each other (not two variations on
the same idea).

<details>
<summary>Hint</summary>

`value = value + 1` and `let cur = value.load(...); value.store(cur + 1, ...)`
are each *two* separate memory operations — a read, then a write. Ask
what ordering strength has to do with a race that happens *between* those
two operations, on a different thread.

</details>

<details>
<summary>Solution</summary>

`volatile`/`SeqCst` guarantees that each individual read and each
individual write is atomic and immediately visible to every other
thread — but it says nothing about what can happen *between* one
thread's read and its own subsequent write. Another thread's increment
can land in that window: thread A reads `value == 10`, thread B reads
`value == 10`, both compute `11`, both store `11` — one increment is
silently lost, and no ordering keyword changes this, because the bug is
about **atomicity of the compound read-modify-write**, not about
visibility or reordering of the two halves individually. This lab's
`counter_update` dataset demonstrates exactly this: its
`volatileSeqCstPublication`/`volatile_seq_cst_publication` variant uses
the strongest built-in ordering and still loses updates under
contention, at roughly the same rate as the plain/`Relaxed` variant.

Two genuinely different fixes: (1) a real read-modify-write primitive —
`VALUE.getAndAddRelease(1)` / `value.fetch_add(1, Ordering::Release)` —
which performs the read-and-write as one indivisible hardware operation;
or (2) mutual exclusion — a lock (or, as this lab's `fenceBased`/
`fence_based` counter variant does, a hand-rolled CAS-guarded spinlock)
that ensures no other thread can even attempt an increment while one is
in progress, so the plain `value = value + 1` inside the critical section
is safe *because nothing else can race it*, not because of any ordering
on `value` itself. A CAS retry loop (this lab's `casLoop`/`cas_loop`) is
a third option, but it is the same "make the RMW indivisible" idea as
(1), just via retry instead of a hardware fused instruction — not a
distinct third category.

</details>

## Exercise 2 — Implementation (add a `Result`-returning single-slot mailbox with a bounded number of publications)

This lab's `MailboxKernel`/`mailbox` module supports exactly **one**
publish-then-claim cycle per `Slot`/`Slot` instance — the CAS variant's
`ready` field goes 0 → 1 → 2 and stops there.

**Task:** implement a variant of the mailbox (in either language, or
both) that supports being reused for `N` sequential publish/claim cycles
on the *same* slot, using the CAS-based claim protocol as your starting
point — the writer must not overwrite `a`/`b` until the previous cycle's
reader has claimed it, and the reader must not claim a slot the writer
hasn't yet published for the *current* cycle.

**Success criteria (measure, don't assert):**

1. A correctness test running `N = MAILBOX_TRIALS` sequential cycles on
   one slot instance never observes a forbidden read and never times out.
2. You can point to the exact extra state (beyond the original 0/1/2
   `ready` values) your design needed to distinguish "cycle 3's
   publication" from "cycle 2's stale, already-claimed publication" — and
   explain why the two-value CAS protocol from Exercise 1's mailbox alone
   is not enough for reuse.
3. You can explain which of this lab's three datasets your reused-slot
   design most closely resembles once you've solved (2) — and why.

<details>
<summary>Hint</summary>

The sequence-flag dataset already solves a version of this exact
problem — a value that gets published and consumed a bounded number of
times in sequence, needing enough state to reject "I already saw this
one." What's the minimum change to the mailbox's `ready` field's *domain*
(not just its protocol) that borrows that idea?

</details>

## Exercise 3 — Evidence interpretation (reading a fence-composition bug report)

Below is a **synthetic teaching example** — a fabricated bug-report-style
log, constructed for this exercise, not captured from any real run. It is
educational material for practicing fence-reasoning only: it is never
used as measurement evidence, never supports this lab's performance
conclusions, and never enters a comparison or maturity calculation. (This
lab's real correctness evidence comes exclusively from the stress-trial
suites in java.md/rust.md, run against the shared fixture with full
provenance.)

A teammate is debugging a seqlock-style publish/read protocol on Java and
reports:

```
Variant                          Failures / 1,000,000 trials
--------------------------------------------------------------
plain access, no fences                          41,209
Opaque access + releaseFence()/acquireFence()        37
Opaque access + fullFence() on publish only           0
```

They conclude: "`releaseFence()`/`acquireFence()` barely helps —
it's basically as broken as no fences at all. Only `fullFence()` actually
works."

**Task:** identify what's wrong with their conclusion, using the ordering
composition rules from this lab's theory page (release = StoreStore +
LoadStore, acquire = LoadLoad + LoadStore, neither includes StoreLoad).
Then state what these three numbers **can** support, precisely.

**Success criteria:** your critique of "basically as broken" is grounded
in the actual failure-rate ratio, not just an appeal to authority ("the
fences do help, trust the docs"); your restated, defensible conclusion is
one this data genuinely supports.

<details>
<summary>Hint</summary>

37 out of 1,000,000 and 41,209 out of 1,000,000 are both "not zero," but
they are not remotely the same *order of magnitude* of brokenness. What
does going from a ~4% failure rate to a ~0.004% failure rate suggest
about how much of the underlying race the release/acquire fences actually
closed — versus what fraction of the *original* window remains open only
because of the missing StoreLoad edge?

</details>

<details>
<summary>Solution</summary>

The conclusion "barely helps, basically as broken" is not supported by a
roughly **1,100x** reduction in failure rate (41,209 → 37 per million).
Release/acquire fences correctly close the overwhelming majority of the
reordering window predicted by the composition rules — they supply
StoreStore, LoadStore, and LoadLoad ordering, which is most of what a
publish/read protocol needs. What they leave open is specifically the
StoreLoad case: a narrow, real, but much rarer reordering opportunity,
consistent with 37 failures rather than tens of thousands. Calling that
"basically as broken as no fences" conflates "not perfectly zero" with
"not meaningfully improved," which the ratio directly contradicts.

What this data **can** support: that a residual, StoreLoad-shaped race
window remained after adding release/acquire fences, and that adding a
full fence closed it (consistent with, though on its own not sole proof
of, the composition-rule explanation). What it **cannot** support: any
claim about the exact numeric failure rate on a different CPU
architecture, JVM version, or trial count — this is one synthetic
scenario at one (fabricated) sample size, and — per this lab's own
"absence of observed failure does not prove correctness" guardrail — even
the `0/1,000,000` full-fence row is evidence at that sample size, not a
proof the race is structurally impossible.

</details>
