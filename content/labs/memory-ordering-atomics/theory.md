# Memory Ordering: VarHandles and Rust Atomics — theory

## Performance question and hypothesis

**Question:** what correctness and performance guarantees do plain,
opaque, acquire/release and sequentially consistent operations provide?

**Hypothesis:** weaker valid orderings can reduce fences and contention,
but incorrect publication creates rare failures that benchmarks alone may
not reveal.

**What would disprove it:** if a weaker-ordering variant (acquire/release)
showed no measurable throughput advantage over the strongest ordering
(sequential consistency) on this lab's datasets, the "weaker ordering
buys real performance" half of the hypothesis would be wrong for these
workloads. If the plain/broken variant *never* produced a forbidden
outcome across a large stress run, or if a "correct" variant *did*
produce one, the "incorrect publication creates failures" half would be
wrong — either the demonstrated mechanism is not real, or this lab's
implementation of it is.

## Learning objective

Explain the spectrum from plain field/variable access through opaque,
release/acquire and sequentially-consistent operations as a spectrum of
**guarantees**, not a spectrum of "speed settings": show what each level
actually promises (atomicity, ordering, visibility), demonstrate a
concrete publication bug that plain access permits, show that a
sequentially-consistent *field* is still not an atomic
read-modify-write, and give a genuine (if narrower) case for standalone
fences alongside opaque access.

## Prerequisites

- The [Memory Ordering in Java and Rust](/lab/memory-ordering/) lab —
  this lab assumes the happens-before vocabulary and the JMM/Rust memory
  model basics that lab establishes; it does not re-derive them.
- Comfortable reading Java `VarHandle` and Rust `std::sync::atomic` code.

## Pre-lab diagnostic

A colleague writes a single-producer/single-consumer flag: the writer
does `value = 42; ready = true;` (both plain fields, no `volatile`, no
`Atomic`), and the reader spins on `while (!ready) {}` then reads `value`.
Their manual test — one run, on their laptop — works every time. Are they
safe to ship this? What, specifically, is missing?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Plain access | An ordinary field/variable read or write with no ordering or atomicity guarantee across threads — the JLS/Rust memory model make no promise a plain write becomes visible to another thread at any particular time, or at all. |
| Opaque (Java) / `Relaxed` (Rust) | Atomicity and freedom from "out-of-thin-air" values, but **no** ordering relative to any other memory operation — a thread reading an Opaque/Relaxed value gets *a* value that was really written, just not necessarily the *most recent* one, and with no guarantee about what else became visible alongside it. |
| Release / Acquire | A one-directional handshake: a release-store makes every write that happened-before it (in the writer's program order) visible to any thread that observes it via a matching acquire-load. Cheaper than full sequential consistency because it only orders *this* writer against *this* reader, not against every thread in the program. |
| Sequentially consistent (`volatile`/`SeqCst`) | The strongest built-in ordering: all sequentially-consistent operations in the program appear to execute in a single, globally agreed-upon order, consistent with each thread's own program order. Strictly stronger than release/acquire, and the only one of the four that gives every thread the same view of "what happened when." |
| Standalone fence | A memory barrier (`VarHandle.releaseFence()`/`acquireFence()`/`fullFence()`, `std::sync::atomic::fence`) placed independently of any specific variable access — orders the *surrounding* memory operations in the issuing thread, rather than being attached to one atomic operation. |
| Read-modify-write (RMW) | A single, indivisible combination of reading a value and writing a new one derived from it (`fetch_add`, `getAndAddRelease`, compare-and-swap). Critically, a strongly-ordered *single* load and a strongly-ordered *single* store, done as two separate operations, are **not** an RMW — nothing stops another thread's write from landing between them. |
| Forbidden outcome | A result the mechanism's specification says must never happen (e.g. a reader observing a "ready" flag but reading stale/default payload) — distinct from a merely *undesirable but possible* outcome like a lost counter update under contention. |

## The ordering spectrum, and what each level actually buys

Four points on one spectrum, from weakest to strongest:

1. **Plain access** — no cross-thread guarantee at all. In Java, a racy
   plain read is *unspecified* (you may see a stale or, for non-`long`/
   `double` fields, a "no worse than a previously written value" result)
   but is not undefined behavior. In Rust, an actual data race on
   non-atomic memory is undefined behavior outright — which is exactly
   why this lab's Rust "plain broken" variant uses `Relaxed` atomics
   rather than a true unsynchronized race (see "Assumptions and scope").
2. **Opaque (Java) / `Relaxed` (Rust)** — every read returns a value that
   really was written (no invented values), and access to that one
   variable is atomic, but the compiler and CPU remain free to reorder
   this operation relative to every *other* memory operation in the
   thread. Useful when you need atomicity (e.g. a counter nobody
   coordinates on) but not ordering.
3. **Release/acquire** (`setRelease`/`getAcquire`, `Ordering::Release`/
   `Ordering::Acquire`) — a release-store and a matching acquire-load on
   the *same variable* form a one-way bridge: everything the writer did
   before the release becomes visible to the reader after the acquire.
   This is enough for a huge fraction of real publication patterns
   (see MailboxKernel below) and is cheaper on most hardware than full
   sequential consistency because it does not need to order this
   operation against every *other* thread's sequentially-consistent
   operations, only against its one handshake partner.
4. **Sequentially consistent** (`volatile` field access in Java,
   `Ordering::SeqCst` in Rust) — release/acquire strength *plus* a total,
   global order over every sequentially-consistent operation in the
   program, agreed on by all threads. This is strictly more than most
   two-thread handoffs need, which is exactly this lab's point about the
   counter-update dataset below: reaching for the strongest ordering does
   not, by itself, make a *compound* operation atomic.

## Two publication protocols, two different minimum requirements

This lab's three datasets are not three unrelated demos — they show that
"how strong an ordering do I need" depends on the **protocol**, not just
on "how important is this data":

- **Single-slot mailbox** (one-shot publish, one-shot read): a single
  release-store paired with a single acquire-load is the textbook
  minimum, and it is sufficient — there is no retry, no second read to
  reconcile.
- **Sequence flag plus payload** (a seqlock: write, write, re-check):
  release/acquire is *also* sufficient here, but only if the reader
  additionally re-validates its first read against a second read of the
  same flag, because the payload sits *between* two flag operations
  rather than strictly before one. Building this lab's own reference
  implementation surfaced this exact gap directly — see "Known traps"
  below for the two, reproduced-on-real-hardware failures found while
  writing it.
- **Counter update** (many writers, one shared counter): neither
  release/acquire nor full sequential consistency, applied to a *field*,
  makes `read, then write` atomic. Only an actual RMW primitive
  (`fetch_add`/`getAndAddRelease`), a CAS retry loop, or mutual exclusion
  does.

## Fences as a distinct axis from access mode

A standalone fence (`releaseFence()`/`acquireFence()`/`fullFence()` in
Java, `fence(Ordering)` in Rust) is not a fifth ordering strength — it is
a *different way to attach* an ordering constraint: instead of tagging
one variable's access with "this load/store is acquire/release," a fence
orders the surrounding code in the issuing thread regardless of which
variable is touched next. This lab's own fence-based variant is honest
about a real limitation this distinction creates: a plain (or even
Opaque/`Relaxed`) variable access is not automatically upgraded to
something a fence can safely "hand off" to another thread purely because
a fence sits next to it — see "Known traps" and java.md/rust.md for the
concrete, reproduced failure and the fix this lab shipped instead.

## Required visualization: happens-before graph (textual model)

A happens-before edge is a promise: "if thread B observes what thread A
did at this point, then B also observes everything A did *before* this
point." For the mailbox dataset's acquire/release variant:

```
Writer thread                       Reader thread
--------------                      --------------
a = PAYLOAD_A        }              spin: read ready (Acquire)
b = PAYLOAD_B        } program      -- observes ready == 1 --
ready.store(1,        } order       -- happens-before edge --
   Release)      -----+------------>  read a, read b
                                        (guaranteed to see PAYLOAD_A/B)
```

The release-store and the acquire-load on the *same variable* are what
create the diagonal edge; without it, the two threads' program orders are
independent and the reader's reads of `a`/`b` could observe anything
written to that memory before the writer thread ever started (see
"Textual fallback" table below for the broken case).

### Textual fallback: what each access mode permits

| Writer op on flag | Reader op on flag | Is the payload write guaranteed visible once the reader observes the flag? |
|---|---|---|
| Plain / Opaque / `Relaxed` | Plain / Opaque / `Relaxed` | No — no happens-before edge is created at all. |
| `setRelease` / `Ordering::Release` | `getAcquire` / `Ordering::Acquire` | Yes — this is the minimum sufficient pairing. |
| `setVolatile` / `Ordering::SeqCst` | `getVolatile` / `Ordering::SeqCst` | Yes — strictly more ordering than needed for this one handoff, but still correct. |
| CAS (any) | CAS (any) | Yes — a successful CAS carries full acquire-and-release semantics by default in both languages. |

## Required visualization: ordering/fence composition table

| Fence | Orders (loads/stores before) against (loads/stores after) |
|---|---|
| `loadLoadFence()` / none direct in Rust (approximated by `fence(Acquire)`) | Loads before < loads after |
| `loadStoreFence()` | Loads before < stores after |
| `storeStoreFence()` | Stores before < stores after |
| `releaseFence()` / `fence(Ordering::Release)` | (Loads+stores) before < stores after — StoreStore + LoadStore |
| `acquireFence()` / `fence(Ordering::Acquire)` | Loads before < (loads+stores) after — LoadLoad + LoadStore |
| `fullFence()` / `fence(Ordering::SeqCst)` | Everything before < everything after, including StoreLoad |

The row that trips up a from-scratch fence-based implementation is the
last one: **only a full fence includes the StoreLoad edge.** Neither
`releaseFence()` alone nor `acquireFence()` alone — nor the two paired
together — provides it. A protocol whose correctness quietly depends on
StoreLoad ordering (this lab's seqlock double-check turned out to be one)
will not be fixed by adding more release/acquire fences; it needs a full
fence, a real RMW, or (as this lab's shipped fence-based variant does) a
release/acquire access mode plus one deliberate additional full fence.

## Required visualization: jcstress/loom outcome matrix (capability-gated)

This lab's design calls for a jcstress (Java) and loom (Rust)
outcome-matrix visualization — an exhaustive interleaving check that
enumerates every legal thread interleaving for a small litmus test and
reports which outcomes are observed. **Neither tool is wired into this
repository's toolchain today** (checked before writing this page — no
other lab in this repository has a working jcstress or loom harness to
build on), so, per this lab's own guardrail against introducing new,
unvetted toolchain dependencies to hit a checklist item, this evidence
source is marked **unavailable by capability detection**, not silently
skipped: the outcome-matrix claim here is instead backed by the same
stress-trial correctness suites (java.md/rust.md) run at the trial counts
in `code/fixtures/`, which is a real but weaker form of evidence — it
samples interleavings rather than exhaustively enumerating them. A
future change that adds a vetted jcstress/loom harness to this repository
generally (not just for this lab) would let this section link a real
exhaustive-interleaving matrix instead.

### Textual fallback (outcome matrix)

| Dataset | Variant | Correctness suite result |
|---|---|---|
| Mailbox | plain broken | Not gated — forbidden-read rate reported honestly |
| Mailbox | acquire/release, volatile/SeqCst, CAS, fence-based | Zero forbidden reads across the trial count, every run |
| Sequence flag | plain broken | Not gated — forbidden-payload rate reported honestly |
| Sequence flag | acquire/release, volatile/SeqCst, CAS, fence-based | Zero forbidden payloads across the trial count, every run |
| Counter update | plain broken, volatile/SeqCst | Not gated — observed total (≤ expected) reported honestly |
| Counter update | acquire/release, CAS, fence-based | Exact expected total, every run |

## Known traps

- **Using performance tests as correctness proof.** A benchmark that
  never crashes or throws is not evidence an ordering choice is correct
  — the plain/broken variants in this lab complete without hanging on
  essentially every run while still sometimes producing a forbidden
  outcome; absence of an exception is not absence of a bug.
- **Mapping Java and Rust orderings mechanically.** `volatile` in Java
  and `Relaxed` in Rust are **not** the same strength — Java's `volatile`
  is sequentially consistent, closer to Rust's `SeqCst`, while Rust's
  weakest atomic ordering (`Relaxed`) is closer to Java's `Opaque`. This
  lab's dataset naming ("volatile/seq-cst publication") is deliberately
  paired to keep that mapping honest rather than implying "volatile ≈
  Relaxed" by proximity in a table.
- **Benchmarking an intentionally racy variant without isolation.** The
  plain-broken and counter-trap variants must run as their own
  correctness trials, never mixed into the same JVM/process invocation
  as a variant being benchmarked for throughput — a racy variant can
  leave shared state (or, in principle, corrupt memory in the Rust case)
  in a way that could affect an unrelated subsequent measurement.
- **Omitting forbidden outcomes.** A results table that only shows
  ops/s for the "correct" variants and silently drops the broken ones is
  not neutral reporting — this lab's benchmark.md and exercises
  deliberately keep the broken variants' observed (not asserted) failure
  rates visible.
- **Two real bugs found while building this lab's own reference
  implementation**, both reproduced repeatedly on real hardware (Apple
  M1 Max/ARM), documented in full in the `SequenceFlagKernel.fenceBased`
  javadoc (java.md links it): (1) a **plain** field is not made visible
  across threads by a nearby fence at all — the field itself needs at
  least Opaque/Relaxed access before a fence has anything to order; and
  (2) even with Opaque/Relaxed access, a seqlock's *double-check* step
  needs a full (StoreLoad) fence, not just the release/acquire pair that
  suffices for the mailbox's one-shot handoff — see the ordering/fence
  composition table above. Both are concrete instances of "looks correct
  against the spec's composition rules" not being the same claim as
  "verified correct on this toolchain," which this lab treats as itself
  a lesson, not an embarrassment to hide.

## Assumptions and scope

- Rust's "plain broken publication" variant uses `Ordering::Relaxed`
  rather than a genuinely unsynchronized data race, because the latter is
  undefined behavior in Rust (unlike Java, where a racy plain read is
  merely unspecified, not undefined) — this is a real semantic difference
  between the two languages' "weakest legal" access, documented here
  rather than glossed over; see java.md/rust.md for the full
  semantic-equivalence contract.
- Correctness (no forbidden outcome for the "correct" variants; observed-
  not-asserted results for the deliberately weak ones) is verified by a
  shared fixture (`code/fixtures/memory-ordering-atomics-fixtures.json`)
  asserted identically by both languages' test suites before any timing
  is trusted.
- jcstress and loom are marked capability-unavailable in this lab rather
  than newly introduced, per the guardrail against adding unvetted
  toolchain dependencies to satisfy a single checklist item; see "Required
  visualization: jcstress/loom outcome matrix" above.
- This lab's stress-trial counts (`MAILBOX_TRIALS`, `SEQFLAG_TRIALS`) are
  sized to keep `mvn test`/`cargo test` fast; they sample interleavings
  rather than exhaustively enumerating them, and a failure to observe a
  forbidden outcome at these counts is evidence, not proof, of
  correctness — exactly the caution the design's own guardrail
  ("no claim that absence of observed failure proves a memory-order
  algorithm correct") requires.

## Pre-lab diagnostic — answer

No, they are not safe to ship this, and "it worked on my laptop" is
close to the least informative test possible here. Two independent gaps:
first, neither `value` nor `ready` has any defined cross-thread ordering
at all, so nothing stops the reader's own CPU core or the JIT/compiler
from observing `ready == true` before `value`'s write is visible to that
core — the classic "publish before payload is visible" bug this lab's
MailboxKernel demonstrates directly. Second, even if that specific
reordering doesn't happen to manifest on their particular CPU
architecture (x86's strong ordering model hides many bugs that a weaker
architecture like ARM will expose), a single manual run proves nothing:
this is a data race, and data races are exactly the kind of bug whose
probability of manifesting depends on scheduling, cache state, and
compiler optimization level — none of which a developer's one manual run
controls or samples representatively. The fix is a release-store on
`ready` paired with an acquire-load on the reader's check (or the
stronger `volatile`/`SeqCst` on both) — see "The ordering spectrum" above
for why release/acquire is already sufficient for this exact one-shot
shape.
