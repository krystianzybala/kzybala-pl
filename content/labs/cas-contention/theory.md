# CAS contention and backoff — theory

## Learning objective

Explain compare-and-set (CAS) as a conditional atomic update, show how
retry loops under contention amplify both coherence traffic and latency,
compare no-backoff, fixed-backoff, and exponential-backoff-with-jitter
retry strategies, and explain why "lock-free" does not automatically mean
"low latency" — contrasting CAS contention against a single-writer
ownership alternative.

## Prerequisites

- The [Memory Ordering in Java and Rust](/lab/memory-ordering/) lab — CAS
  is itself an atomic read-modify-write, and this lab assumes you already
  know the atomicity/visibility/ordering distinction from there.
- The [Cache Coherence and MESI](/lab/mesi/) lab is useful background for
  "cache-line ping-pong" below, though not required.

## Compare-and-set (CAS)

**Compare-and-set** is a single hardware-atomic instruction (`cmpxchg` on
x86, `ldxr`/`stxr` or `cas` on ARM) with the shape: *"if the current value
equals what I expect, replace it with a new value — atomically, as one
indivisible step; tell me whether it worked."* Unlike a plain store, CAS
can **fail**: if another thread changed the value between when this thread
last read it and when it attempts the CAS, the expected value no longer
matches, and the operation does nothing except report failure.

Every CAS-based algorithm is therefore built around a **retry loop**:

```text
loop:
    old = read current value
    new = compute desired value from old
    if compareAndSet(old, new): return   // success
    // else: someone else moved it first — retry
```

This is the fundamental building block of lock-free data structures and
atomics libraries (`AtomicLong.updateAndGet`, Rust's
`compare_exchange`/`fetch_update`) — no lock is ever held, no thread ever
blocks waiting for another, but a thread *can* spin through many failed
attempts before it succeeds.

## Contention and cache-line ping-pong

Under low contention (one thread touching the value, or threads touching
it rarely), CAS usually succeeds on the first attempt: cheap, and never
blocks. **Under contention** — many threads repeatedly attempting CAS on
the *same* location — a different cost dominates: every attempt, whether
it succeeds or fails, requires the attempting core to have (conceptually)
exclusive access to that cache line, exactly as a write does in the MESI
model. When several cores are constantly attempting CAS on one location,
the line is repeatedly invalidated and re-fetched, core to core — the same
cache-line ping-pong the False Sharing and MESI labs describe, except here
it's not an accident of layout, it's the direct, unavoidable consequence of
many threads legitimately wanting to update the *same* logical value.

More contenders does not just mean more total work — it means a **higher
fraction of that work is wasted retries**, because every failed attempt
still paid the full coherence cost of contending for the line, produced no
useful progress, and has to be redone. This is what "contention collapse"
means: throughput does not merely plateau as contender count grows, it can
fall, because the wasted-retry fraction grows faster than the useful-work
fraction.

## Backoff and jitter

The lever available once contention is diagnosed: **don't retry
immediately.**

- **No backoff.** Retry again as soon as the previous attempt fails. Under
  light contention this is fine — even optimal. Under heavy contention, it
  maximizes the odds that every contender lands on the line again at once,
  compounding the collision.
- **Fixed backoff.** Wait a constant delay after each failure before
  retrying. Spaces out contenders, reducing simultaneous collisions — but a
  delay too short does little, and a delay too long wastes latency even
  when contention has already cleared.
- **Exponential backoff.** Double the delay (up to a cap) after each
  *consecutive* failure for a given thread, resetting once it succeeds.
  Adapts to observed contention: a thread that's failing repeatedly backs
  off more, a thread that just succeeded goes back to trying promptly.
- **Jitter.** Add a randomized (or, in a deterministic teaching model,
  pseudo-randomized) perturbation to the backoff delay so that contenders
  who all started backing off at the same moment don't all retry at
  exactly the same moment again — otherwise backoff alone can synchronize
  contenders into lockstep collisions instead of preventing them.

**None of this is free, and none of it is universal.** A backoff delay
that's well-tuned for one contention level can be actively wrong for
another — see "When backoff helps, and when it doesn't" below.

## The single-writer alternative

CAS retry loops are not the only way to update shared state safely. If the
*architecture* can be changed so that exactly one thread ever owns the
right to write a given piece of state — message-passing it work instead of
sharing the state directly, or partitioning data so each thread owns a
disjoint slice — there is no contention to manage at all: every write
succeeds on the first attempt, because nothing else is racing for it. This
lab's "Single-writer comparison" scenario makes the contrast concrete: the
same total number of updates, with zero retries and zero cache-line
ping-pong, because there was never more than one writer.

This is not a claim that single-writer designs are always better — they
trade away the flexibility of "any thread can update this," and moving
that constraint elsewhere in a system doesn't make the underlying
coordination problem disappear, it relocates it. It is a genuine
alternative worth knowing about before reaching for CAS by default.

## ABA, briefly

A classic CAS hazard: a thread reads value `A`, computes what it wants to
write based on `A`, but before its CAS executes, some other thread changes
the value from `A` to `B` and back to `A` again. The CAS still succeeds —
the value *is* `A` — even though the state genuinely changed and changed
back in between, which can silently invalidate an assumption the first
thread's logic depended on (for example, if `A` was a pointer to a node
that got freed and a *different* node happened to get allocated at the
same address).

This lab does not implement or solve ABA — that requires either a
versioned/tagged CAS (a value+counter pair updated atomically together, so
the counter differs even if the value doesn't), hazard pointers, or
epoch-based reclamation, each a substantial topic on its own (see
"Non-goals"). The point here is recognition: **a successful CAS proves the
value is currently what you expected, not that nothing happened in
between.**

## Fairness and starvation

A CAS retry loop makes no fairness promise. Under sustained contention, it
is possible — not likely in most real workloads, but possible — for one
thread to fail repeatedly while others succeed, in principle indefinitely.
This is different from a fair lock (many lock implementations queue
waiters in arrival order); a bare CAS loop has no queue and no memory of
who tried first. Whether this matters depends entirely on the workload: a
short, bounded retry loop under typical contention is usually fine in
practice; a system with an adversarial or highly skewed contention pattern
may need a fairness mechanism CAS alone does not provide.

## When backoff helps, and when it doesn't

- **Helps:** many threads, sustained contention, a value that's genuinely
  hot. This is where uncontrolled retries would otherwise waste the most
  coherence traffic.
- **Doesn't help / actively hurts:** light or no contention, where backoff
  only adds latency to what would already have succeeded immediately; or a
  fixed delay poorly matched to the actual contention level.
- **Workload-sensitive, not a universal rule.** The right backoff
  strategy (none, fixed, exponential, with how much jitter) depends on
  contender count and attempt frequency — this lab's scenarios are
  illustrative comparisons at one fixed contention level, not a formula
  that transfers unchanged to every workload.

## Non-goals

- A full hazard-pointer or epoch-based-reclamation laboratory — ABA
  solutions are substantial topics of their own.
- A complete catalogue of every ABA mitigation technique.
- A universal claim that backoff always helps — see above.

## Limitations of this model

- The interactive model schedules contenders in a fixed, deterministic
  round-robin order and uses a deterministic (non-random) jitter formula —
  real schedulers and real hardware timing are neither round-robin nor
  perfectly predictable.
- "Completion latency" in the model is a simulated step count, not a
  measured time — see the benchmark section below for real, disclosed
  timing data.
- Cache-line ping-pong is described conceptually here; see the
  [Cache Coherence and MESI](/lab/mesi/) lab for the underlying mechanism.
