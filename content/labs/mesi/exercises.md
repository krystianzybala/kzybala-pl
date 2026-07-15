# Cache coherence and MESI — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (trace the states or find the lie)

A colleague describes this two-core sequence over one cache line, with
both cores starting with the line uncached (Invalid):

1. Core 0 reads → "line is now **Exclusive** in core 0."
2. Core 1 reads → "core 1 gets a copy; both cores now hold **Shared**."
3. Core 0 writes → "core 0's copy becomes **Modified**; core 1's copy
   stays Shared — reads don't need to change, only writes do."
4. Core 1 reads → "hits its own Shared copy; no bus traffic."

**Task:** exactly one transition in this story violates the MESI
invariant. Find it, state the invariant it violates, and give the
corrected sequence from that point on.

**Success criteria:** you name the invariant (not just the wrong step),
your corrected sequence includes the coherence transaction that step 3
must broadcast, and you can say what core 1's read in step 4 actually
costs after the correction.

<details>
<summary>Hint</summary>

Can Modified-here and Shared-elsewhere coexist for the same line? What is
the *first* thing a core must do before its write may proceed when other
copies exist?

</details>

<details>
<summary>Solution</summary>

Step 3 is the lie. The invariant: **Modified is exclusive** — a line may
be Modified in at most one cache, with every other copy Invalid; Modified
coexisting with a Shared copy elsewhere would let core 1 read a stale
value forever. Core 0's write to a Shared line must first broadcast an
invalidation (upgrade/RFO); only when core 1's copy is Invalid does core
0's line become Modified. Corrected step 4: core 1's read *misses* (its
copy is Invalid) and is served by a cache-to-cache transfer from core 0's
Modified copy — the transfer `perf c2c` counts as a HITM — typically
downgrading core 0 to Shared (or Owner, in MOESI variants). That miss +
transfer is the cost the "no bus traffic" claim hid.

</details>

## Exercise 2 — Implementation (make the invalidation visible)

The lab's companion code (`code/java/`, `code/rust/`) contains
`SharedWriterExample` / `SingleOwnerExample`: the same logical work with
and without cross-core write sharing of one line.

**Task:** extend the example with a third variant, `ReadMostlyExample`:
one writer thread updates the line at a low, fixed rate (e.g. 1,000
writes/s) while N reader threads read it in a tight loop. Predict, then
measure, where it lands relative to the other two.

**Success criteria (measure, don't assert):**

1. Correctness: readers always observe a value the writer actually wrote
   (add the assertion — a torn or stale-forever read fails the exercise).
2. Your written prediction, committed *before* running, states where
   read-mostly falls between single-owner and write-shared and why (Shared
   copies serve reads locally; each write invalidates all of them, so cost
   scales with write rate × reader count, not read rate).
3. Your measurement on your machine is consistent with the prediction's
   *ordering* (not its absolute numbers), and you can explain any surprise
   using the state transitions from this lab.
4. Hardware-counter confirmation is explicitly out of scope on a
   development workstation — note it as awaiting the dedicated
   native-Linux host workflow (see the false-sharing lab's evidence
   runner) rather than substituting a guess.

<details>
<summary>Hint</summary>

Keep the writer's rate fixed and sweep reader count. If read-mostly
performs *identically* to write-shared in your first attempt, check
whether your "readers" are accidentally writing (e.g. a shared
iteration counter — that's a second, unintended write-shared line).

</details>
