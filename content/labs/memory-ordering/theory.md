# Memory ordering in Java and Rust — theory

## Performance question and hypothesis

**Question:** what can compiler and CPU reordering do to program order,
and how do VarHandles and Rust atomics constrain it?

**Hypothesis:** without an ordering constraint, two writes to different
locations may become visible to another thread out of program order — and
a release/acquire pair on the publishing flag restores exactly the edge a
publication pattern needs, at lower cost than full sequential consistency
on weakly-ordered hardware.

**What would disprove it:** observing even one stale read through a
correctly release/acquire-published pointer would falsify the model's
central guarantee. The reverse is deliberately asymmetric: a
plain/relaxed publication test that never happens to fail does **not**
prove it correct — absence of an observed reordering is not evidence of
ordering, which is itself one of this lab's core lessons. On the cost
side: if release/acquire and sequentially-consistent operations compiled
to identical instructions and identical cost on every tier of hardware,
the "pay only for the ordering you need" guidance would be empty.

## Learning objective

Explain why the order two threads observe each other's writes is not the
same as the order those writes appear in source code, distinguish
**visibility** from **atomicity** from **ordering**, explain acquire/release
publication and why it works, and map Java's VarHandle access modes and
Rust's atomic orderings without assuming a direct one-to-one equivalence.

## Prerequisites

- The [Cache Coherence and MESI](/lab/mesi/) lab — this lab assumes you
  already know that coherence guarantees *agreement* on a single location's
  value. This lab is about something coherence does **not** give you: the
  order in which writes to *different* locations become visible to another
  thread.
- Comfortable reading Java or Rust concurrent code (atomics, threads).

## Program order vs. observed order

**Program order** is the order your source code writes instructions in.
**Observed order** is the order another thread actually sees those
instructions' effects take place — and the two are not the same thing.

Two independent mechanisms can separate them:

- **Compiler reordering.** A compiler is free to reorder, merge, or
  eliminate memory accesses that have no data dependency between them,
  as long as the reordering is invisible to *that thread's own*
  sequential execution — it does not have to preserve an order another
  thread might be relying on.
- **CPU/hardware reordering.** Even with the compiler's output fixed, a
  CPU core can execute and retire instructions out of order, and — the
  mechanism this lab's interactive model focuses on — a core can hold its
  own stores in a local **store buffer** before they become visible to
  other cores, so a later independent load can complete before an earlier
  store has drained.

Neither mechanism is a bug. Both are legal, performance-motivated behaviour
in the *absence of a rule telling them not to do this*. Memory-ordering
annotations (`volatile`, `Acquire`/`Release`, fences) are exactly that rule.

## Visibility, atomicity, and ordering are three different guarantees

These get conflated constantly, and the myths in this lab mostly come from
conflating them:

- **Atomicity** — an operation appears to happen as one indivisible step;
  no thread can observe it "half-done." A `fetch_add` either applies fully
  or not at all from any observer's point of view.
- **Visibility** — once a write happens, when (if ever) does another
  thread observe it? A non-atomic, non-synchronized write can be delayed
  indefinitely from another thread's point of view, cached in a register
  or a store buffer.
- **Ordering** — if a thread observes write A, does it also observe every
  write that happened-before A in program order? This is the question
  acquire/release exists to answer, and it is independent of the first two.

An operation can have any combination: a plain `long` write in Java is
non-atomic (on 32-bit JVMs, historically) and gives no visibility or
ordering guarantee. A `Relaxed` atomic in Rust is atomic but gives **no**
ordering guarantee about other memory operations. A `volatile`/`SeqCst`
access gives all three.

## happens-before and data races

**happens-before** is a partial order the language memory model defines
over a program's operations. If operation A happens-before operation B,
every effect of A is guaranteed visible to B. Program order within a single
thread always contributes happens-before edges; synchronizing operations
(a release paired with an acquire that observes it, a lock release paired
with the next acquire of the same lock) contribute cross-thread edges.

A **data race** is two accesses to the same memory location, from
different threads, with no happens-before edge between them, at least one
of which is a write. Where the language memory model leaves race behaviour
undefined (C++, Rust `unsafe`) or "unspecified but not crash-the-JVM"
(the Java Memory Model gives *some* guarantees even to racy plain reads —
see "Java Memory Model caveats" below), a race is something to eliminate,
not reason carefully about.

## The message-passing litmus test: broken vs. fixed publication

The classic **publication** problem: thread 0 prepares some data, then sets
a flag to say "ready." Thread 1 waits for the flag, then reads the data.

```text
Thread 0                      Thread 1
data = 1                      while (!flag) {}
flag = 1                      read data
```

With **plain** (unsynchronized) accesses, this is legal but broken: nothing
stops thread 0's two writes from becoming visible to thread 1 out of
program order, and nothing stops thread 1 from reading `data` and observing
a value from *before* thread 0's write, even after observing `flag == 1`.
The interactive model's "Broken publication" scenario makes this concrete:
it flushes the `flag` write to memory before the buffered `data` write, and
lets thread 1 observe `flag == 1, data == 0` — the exact shape of this bug.

The fix is a **release store** on the publishing side paired with an
**acquire load** on the observing side. A release write is guaranteed to
make every write that precedes it in program order visible no later than
itself. An acquire read that observes a release write's value establishes
a happens-before edge from every instruction before that release to every
instruction after that acquire. The "Release/acquire message passing"
scenario shows the same two variables, fixed: once thread 1's acquire read
observes `flag == 1`, its subsequent plain read of `data` is guaranteed to
see `1`.

## The store-buffering litmus test

A different, equally classic litmus test isolates *only* the store-buffer
effect, with no dependency between the variables at all:

```text
Thread 0                      Thread 1
x = 1                         y = 1
read y                        read x
```

Under relaxed/weak ordering, **both threads can observe 0** for the
*other* thread's write — each thread's own store can sit in that thread's
store buffer, invisible to the other thread, while its own subsequent
(independent-address) load is satisfied directly from memory. This looks
paradoxical (`if thread 0 didn't see thread 1's write, how could that
write not have happened yet — but thread 1's read of x also sees 0`) but
it is a real, legal outcome under a weak/relaxed memory model, and it is
precisely what sequential consistency forbids. The "Store-buffering litmus
test" scenario demonstrates this outcome; the "Sequential consistency
comparison" scenario re-runs the identical test under SeqCst ordering,
where the both-see-0 outcome cannot occur.

## Java Memory Model (JMM) caveats

- **`volatile` is not "instantaneous" or "flushes the cache."** It
  establishes happens-before ordering (a volatile write happens-before a
  subsequent volatile read of the same field that observes it) — the JMM
  says nothing about cache flushing, because the JMM is a language-level
  contract, not a hardware specification. How a given JVM/CPU combination
  implements that contract (fences, cache-coherence protocol messages) is
  an implementation detail.
- **VarHandle access modes are not a strict weak-to-strong ladder that maps
  index-for-index onto Rust's five orderings.** `getPlain`/`setPlain` give
  no atomicity guarantee for anything wider than an `int` on some JVMs
  historically and no ordering guarantee at all. `getOpaque`/`setOpaque`
  add atomicity (bitwise, for the accessed field) and *coherence* (a total
  order per-location) but not happens-before ordering relative to *other*
  variables. `getAcquire`/`setRelease` add one-directional
  happens-before edges. `getVolatile`/`setVolatile` add the full JMM
  volatile contract, including "no reordering across it" from the
  compiler and runtime's point of view.
- **A single plain (non-volatile, non-atomic) read/write of a `long` or
  `double` field is only guaranteed atomic since JLS updates that removed
  the old 32-bit "may be torn" carve-out for ordinary JVMs** — do not
  assume every runtime you might target gives you this for free without
  checking which JLS version and mode applies.

## Rust memory-model caveats

- **Rust's atomic orderings (`Relaxed`, `Acquire`, `Release`, `AcqRel`,
  `SeqCst`) are defined in terms of the C++11 memory model**, adapted into
  Rust's own reference — they are close cousins of, not identical
  twins to, Java's VarHandle modes. Do not assume `Acquire` in Rust and
  `getAcquire` in Java compose identically with every other primitive in
  the other language.
- **`Relaxed` guarantees atomicity and a total modification order per
  location, and nothing about ordering relative to any other location.**
  It is exactly the "atomic but not ordered" corner of the
  visibility/atomicity/ordering distinction above.
- **`SeqCst` is the strongest and most expensive ordering, and is not
  required "just in case."** It adds a single total order that *all*
  SeqCst operations across *all* threads agree on, on top of
  acquire/release — useful specifically when a program's correctness
  depends on every thread agreeing on the relative order of multiple
  independent SeqCst operations (the store-buffering litmus test above is
  the canonical example of a case where `Acquire`/`Release` alone is not
  enough and `SeqCst` is).
- **Getting this wrong is undefined behaviour in Rust, not a slow-but-safe
  fallback.** A relaxed/acquire/release choice that doesn't actually
  provide the ordering your algorithm depends on is a real correctness
  bug, exactly as unsynchronized access is in Java — Rust's type system
  does not check memory-ordering correctness for you.

## Myths this lab explicitly rejects

- **"Acquire/release flushes the CPU cache."** No language memory model
  talks about cache flushing. Coherence (see the MESI lab) already
  guarantees agreement on a single location; acquire/release is about
  *ordering across locations*, a different guarantee, implemented however
  a given compiler/CPU pair chooses (often nothing resembling a "flush").
- **"`volatile` (Java) makes an access globally instantaneous."** It makes
  it *ordered* relative to other volatile/synchronizing accesses via
  happens-before — not instantaneous, not "every other thread sees it the
  microsecond it happens."
- **"Atomics automatically make a compound algorithm correct."** An atomic
  increment is atomic; a read-modify-write built from two separate atomic
  operations (check-then-act) is not, unless you use a single atomic RMW
  primitive (compare-and-set, fetch-add) for the whole operation.
- **"SeqCst (or `volatile`/full fences) is required everywhere, to be
  safe."** Weaker orderings are not a shortcut for people who don't
  understand the strong ones — they are the correct, sufficient, and
  cheaper choice for a large fraction of real synchronization patterns
  (see "When weaker orderings are enough" below). Defaulting to the
  strongest ordering everywhere is not automatically "safer" so much as it
  is leaving performance on the table without a specific reason.

## Non-goals

- A full formal memory-model proof system (this lab teaches the mental
  model, not the axiomatic semantics used to verify compilers).
- Cycle-accurate CPU simulation of any specific processor's store buffer.
- Claiming acquire/release "flushes caches" (see myths above).
- Treating the Java and Rust memory models as identical — they are
  independently specified, and this lab states every place they diverge.

## Limitations of this model

- The interactive model uses an explicit "flush" step to make store-buffer
  timing visible and steppable. Real store buffers drain asynchronously,
  on hardware-determined schedules this lab does not simulate — the model
  fixes *one* legal, illustrative interleaving per scenario, not every
  interleaving a real CPU could produce.
- Only two threads and a handful of variables are modeled. Real programs
  have far more complex happens-before graphs.
- The happens-before graph shown is the direct synchronizes-with edge for
  this scenario's specific release/acquire pair, not a full transitive
  closure over an arbitrary program.
