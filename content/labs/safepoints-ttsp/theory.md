# Safepoints and time to safepoint — theory

## Performance question and hypothesis

**Question:** why can a short stop-the-world operation still produce a
long application pause?

**Hypothesis:** pause time includes both safepoint operation duration
and time for threads to reach a safepoint; long loops and native
sections can dominate TTSP.

**What would disprove it:** if application-stopped time always equaled
the safepoint operation's own duration regardless of thread count or
loop shape, if a thread blocked in native code delayed a safepoint the
same way a busy Java thread does, or if more threads never measurably
changed time-to-safepoint, the premise would be wrong. Every dataset in
this lab computes the identical, deterministic sum — worker count and
polling behavior are the only things that change, and this lab's real
evidence (both languages, measured directly, not estimated) is checked
against that. **How long threads take to cooperate with a pause is a
real, separate cost from the pause operation itself.**

## Learning objective

Separate time-to-safepoint (TTSP) from the safepoint operation's own
duration, recognize which code shapes are and are not cooperative, and
reason about the coordination cost of stopping N threads instead of one.

## Prerequisites

- The [JIT pipeline](/lab/jit-pipeline/) lab (introduces safepoints as a
  concept) and the [Clocks, latency histograms and percentiles](/lab/clocks-latency-histograms/)
  lab (this lab reports percentiles and raw timelines for exactly the
  reasons that lab establishes — an average pause time hides the cases
  that matter).

## Pre-lab diagnostic

A team runs `jcmd <pid> GC.run` (an explicit, on-demand full GC) and
observes a 400 ms application pause. The GC log reports the collection
itself took 6 ms. Nobody changed the heap size or collector between two
otherwise-identical runs, yet the *pause* varies by 2 orders of
magnitude between runs while the *collection time* stays roughly
constant. What is most of that 400 ms actually measuring?

(Answer at the end of this page.)

## The mechanism: stopping is a negotiation, not a switch

- **A safepoint is a point where every thread's state is inspectable
  and consistent.** The JVM cannot safely run a GC, a deoptimization, or
  several other VM operations while a thread might be mid-mutation of a
  heap-visible reference. A "stop the world" operation therefore has two
  phases: get every relevant thread to a safepoint, then do the actual
  operation.
- **Time to safepoint (TTSP) is phase one's cost.** From the moment a
  safepoint is requested, the VM waits for every thread that could be
  running Java bytecode to reach a point where it is safe to pause —
  typically a poll inserted at loop back-edges and method returns/entries.
  A thread already parked, blocked, or in native code is *already*
  safepoint-safe and costs nothing extra to wait for; a thread actively
  running Java bytecode must reach its next poll point.
- **The safepoint operation itself is phase two.** Once every thread has
  reached the safepoint, the VM performs the actual work (a GC pass, a
  deoptimization, a biased-lock revocation, …) and then resumes every
  thread. This lab's `jdk.ExecuteVMOperation` evidence is exactly this
  phase's duration, separate from TTSP.
- **Application stopped time is the sum of both, plus a small resume
  phase.** A 6 ms GC can sit inside a 400 ms pause if TTSP alone was
  394 ms — the operation being fast tells you nothing about whether the
  *coordination* to reach it was fast, which is precisely this lab's
  hypothesis.
- **A loop's poll density determines its cooperativeness.** Modern
  HotSpot inserts safepoint polls at loop back-edges, with loop strip
  mining bounding how much work a counted loop can do between polls even
  for very long loops (`-XX:+UseCountedLoopSafepoints`, the JIT's
  default). Disabling that mechanism (`-XX:-UseCountedLoopSafepoints`,
  this lab's `longLoopSparsePolls` variant) removes that bound — how much
  it actually widens TTSP for a given loop shape is an empirical,
  host- and JDK-build-specific question this lab's real evidence answers
  directly, not a guaranteed multiplier.
- **A thread blocked in native code is not the villain it looks like.**
  While a thread executes JNI/FFM native code, it cannot touch
  heap-managed objects and the JVM already treats it as safepoint-safe —
  the VM does not wait for it. The cost shows up on the *other* side:
  when the native call returns, that thread must itself wait if a
  safepoint is in progress before it may re-enter Java code. This lab's
  `threadInNativeCall` variant is built to demonstrate this precisely
  (and to correct the common, opposite assumption).
- **More threads means more coordination, even if most of them are
  idle.** Every JVM thread the safepoint protocol tracks is one more
  participant in the handshake — a thread that is merely parked or
  sleeping is safepoint-safe and does not block TTSP the way a
  bytecode-running thread would, but the bookkeeping to confirm that
  still costs something per thread. This lab's `manyIdleThreads` variant
  isolates that per-thread coordination tax from any actual work.
- **Allocation-pressure-triggered pauses are organic, not scripted.**
  A safepoint requested explicitly (`System.gc()`, this lab's other
  variants) and a safepoint triggered because the allocator ran out of
  space in the current region are the identical JVM mechanism, but the
  *frequency and timing* of the latter depends entirely on the
  application's own allocation behavior — a fact worth stating plainly
  rather than assuming every pause was deliberately requested.
- **Rust has none of this built in, and building it is instructive.**
  There is no JIT-inserted polling, no GC, and no safepoint concept in
  the Rust language or its default runtime. If you want threads to
  cooperatively pause, you build the coordination explicitly — a shared
  flag, a poll point you choose, an acknowledgment counter — which is
  exactly what this lab's Rust track does, and exactly why its cost model
  ends up structurally different from Java's (rust.md).

## Visualization 1: safepoint handshake timeline (deterministic)

The three phases every safepoint episode goes through, and which JFR
event reports which one — not a measurement, the textbook decomposition
this lab's harness relies on directly:

| Phase | What happens | JFR event | Duration = |
|---|---|---|---|
| 1. Time to safepoint (TTSP) | every required thread reaches a poll point | `jdk.SafepointBegin` | that event's own `duration` field |
| 2. Safepoint operation | the actual VM operation runs | `jdk.ExecuteVMOperation` (where `safepoint=true`) | that event's `duration` field |
| 3. Resume | threads are released to continue | `jdk.SafepointEnd` | that event's `duration` field |

All three share a `safepointId`, which is how this lab's harness
correlates them into one episode (java.md).

## Visualization 2: TTSP decomposition chart (illustrative pattern)

A generic sketch of how the three phases can dominate differently across
this lab's variants — **illustrative of the general shape, not extracted
from a live run of this lab's code**; the real evidence is this lab's own
harnesses (benchmark.md):

```text
cooperativeLoop, one worker:      [TTSP: tiny][operation][resume: tiny]
manyIdleThreads:                  [TTSP: larger — per-thread handshake tax][operation][resume]
longLoopSparsePolls + native call: [TTSP: can dominate the whole pause][operation][resume]
```

## Visualization 3: thread-state view (conceptual model)

What each variant's participating threads are doing at the moment a
safepoint is requested — the direct explanation for why TTSP differs:

| Variant | Thread state(s) at request time | Expected TTSP contribution |
|---|---|---|
| cooperativeLoop | running Java bytecode, polling frequently | small — reaches the next poll quickly |
| longLoopSparsePolls | running Java bytecode, polling rarely | can be large — waits up to a whole unpolled stretch |
| threadInNativeCall | one thread in native code (already safepoint-safe) | that thread contributes ~nothing; its own resume is delayed instead |
| manyIdleThreads | many threads parked/sleeping (safepoint-safe) | small per thread, but real in aggregate — per-thread handshake cost |
| allocationPressureTrigger | running Java bytecode, triggered organically | comparable to cooperativeLoop's TTSP; the *frequency* of episodes is what differs |

Textual fallback for all three visualizations: a safepoint pause is
TTSP plus operation time plus a small resume phase; a thread's
contribution to TTSP depends on whether it was already safepoint-safe
(idle, native) or actively running Java code that has to reach its next
poll point, and idle/native threads are cheap individually but not free
in aggregate.

## Terminology

- **Safepoint** — a point at which every relevant thread's state is
  consistent and inspectable, required before certain VM operations.
- **Time to safepoint (TTSP)** — the time from a safepoint request until
  every required thread has reached one.
- **VM operation** — the actual work performed once all threads are
  stopped (GC, deoptimization, biased-lock revocation, …).
- **Application stopped time** — TTSP plus the VM operation's duration
  plus the resume phase; the full, user-visible pause.
- **Cooperative polling** — a thread voluntarily checking whether a
  safepoint has been requested, typically at loop back-edges and method
  boundaries.

## Assumptions and scope

- Every dataset computes the identical deterministic sum regardless of
  worker count or polling behavior (java.md, rust.md); TTSP and pause
  time are measured, never correctness-checked, because they are not
  deterministic quantities.
- `allocationPressureTrigger`'s number of captured safepoint episodes is
  intentionally not fixed — organic GC triggering is the entire point of
  that variant, documented explicitly rather than forced to match the
  other variants' deterministic trigger count.
- The Rust track's coordination is built explicitly by this lab's own
  code, not provided by the language or a runtime; its cost model is
  expected to differ structurally from Java's, and that difference is
  this lab's central Rust-track finding (rust.md), not a gap to close.

## Pre-lab diagnostic — answer

Most of that 400 ms is very likely time-to-safepoint, not the collection
itself: a 6 ms operation time inside a 400 ms pause means roughly 394 ms
was spent waiting for every relevant thread to reach a poll point before
the collection could even begin. Because TTSP depends on what every other
thread in the process happens to be doing at request time (running a
long, sparsely-polled loop; blocked in native code that just returned;
competing for CPU with many other threads), it can vary enormously
between two runs with an *identical* collection, which is exactly why
"blaming GC for all pauses" is this lab's first named trap — the
collector did the same 6 ms of work both times; the coordination cost
around it did not.
