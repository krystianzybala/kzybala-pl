# Locks, spin waiting and parking — theory

## Performance question and hypothesis

**Question:** when is a lock cheaper than a lock-free algorithm, and when
should a waiter spin, yield or park?

**Hypothesis:** critical-section length, contention and oversubscription
determine the winner; lock-free is not a magic adjective that exempts
code from physics.

**What would disprove it:** if a lock-free CAS loop outperformed a mutex
at every contention level and critical-section length tested (no
crossover point to explain); if the "long critical section" variant did
not cost measurably more than "short contended" under otherwise identical
worker counts (critical-section length wouldn't actually matter); or if
the spin-then-park hybrid showed no reduction in CPU consumption relative
to pure spinning under sustained contention (the entire premise of
"sometimes it's cheaper to sleep" would be wrong) — any of these would
mean the model taught here is wrong for this workload.

## Learning objective

Explain that "lock-free" and "fast" are not synonyms: show a concrete
case where a CAS retry loop is *worse* than a mutex (long critical
sections, high contention), contrast the three ways a thread can wait
(spin, yield, park) and their real trade-off (latency to acquire vs. CPU
burned vs. context-switch cost), and measure CPU consumption alongside
throughput rather than throughput alone.

## Prerequisites

- The [MPSC Queues and Producer Contention](/lab/mpsc-contention/) lab —
  this lab's CAS-loop variant and its failed-CAS accounting are the same
  vocabulary that lab's shared-claim-point contention uses; understanding
  why a CAS can fail and retry is assumed.
- The [Memory Ordering: VarHandles and Rust Atomics](/lab/memory-ordering-atomics/)
  lab — the ordering used by this lab's CAS loop and spin-then-park
  hybrid's flag is not re-derived here.

## Pre-lab diagnostic

An engineer replaces a `synchronized` block guarding a simple counter
increment with a CAS retry loop on an `AtomicLong`, expecting it to be
faster "because it's lock-free." Under low contention (2 threads) it is
faster. Under high contention (32 threads all incrementing constantly),
it becomes *slower* than the lock it replaced. What property of the
critical section and the contention level explains this reversal?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Critical section | The code between acquiring and releasing exclusive access — its *length* (how much work happens while exclusive access is held) is a first-class variable in this lab, not an afterthought. |
| Spin waiting | A waiter repeatedly checks (in a tight loop) whether it can proceed, burning CPU the whole time but avoiding a context switch if the wait is short. |
| Parking | A waiter voluntarily yields the CPU to the scheduler (`LockSupport.park()` in Java, `std::thread::park()` in Rust) and is woken (`unpark`) by whoever releases the resource — costs a real context switch but burns no CPU while waiting. |
| Spin-then-park hybrid | A wait strategy that spins for a bounded number of attempts, then falls back to parking — a bet that most waits are short (worth spinning for) but a few are long (not worth burning CPU on indefinitely). |
| Oversubscription | Running more runnable threads than the host has cores for — turns every wait strategy's trade-offs up: spinning wastes CPU other runnable threads actually need, and parking's context-switch cost is paid more often as the scheduler juggles more threads than cores. |
| Fairness (locks) | Whether waiters are served roughly in the order they arrived, or whether some waiters (e.g. ones that keep re-arriving with the lock still contended) can repeatedly "jump the queue" ahead of ones that have been waiting longer. |

## Required visualization: wait-strategy cost model

A conceptual view of what each wait strategy actually pays for while a
resource is held by someone else:

```
Spin:      [CPU burned burned burned burned] -> acquire (no context switch)
Yield:     [CPU burned, but yields each iteration] -> acquire (cheaper spin, still no full switch)
Park:      [thread descheduled, 0 CPU] -----------> [context switch in] -> acquire
Spin-then- [CPU burned for N attempts] -> [thread descheduled, 0 CPU] -> [context switch in] -> acquire
  park
```

### Textual fallback (wait-strategy cost model)

| Strategy | CPU while waiting | Latency to acquire once free | Best when |
|---|---|---|---|
| Spin | High (constant) | Lowest (no context switch) | Wait is expected to be very short |
| Park | None | Higher (context switch in both directions) | Wait is expected to be long, or CPU is scarce/shared |
| Spin-then-park | High for a bounded window, then none | Low if the wait is short; pays the park cost only if it wasn't | Wait length is unpredictable — this lab's actual variant |

## Required visualization: contention-vs-mechanism crossover

A conceptual view of why "lock-free" doesn't uniformly win, as a function
of critical-section length and contention level:

```
cost
  |                                    CAS loop (retries compound with
  |                                     contention AND critical-section
  |                                     length together)
  |                              ,-----
  |                        _____/
  |     mutex        _____/
  |   ______________/
  +------------------------------------------------> contention level
    low                                          high
```

### Textual fallback (contention-vs-mechanism crossover)

| Region | Expected relative cost | Why |
|---|---|---|
| Low contention, short critical section | CAS loop ≤ mutex | Few retries; no blocking/context-switch overhead to pay either way |
| High contention, short critical section | Comparable, workload-dependent | Both mechanisms pay a real cost; which wins depends on the exact retry vs. block/wake trade-off on this host |
| High contention, long critical section | CAS loop can exceed mutex | Every failed CAS attempt re-does *nothing useful* and retries immediately; a blocked mutex waiter, by contrast, is descheduled and costs nothing while another thread's long critical section runs |

## Known traps

- **Running only uncontended tests.** The uncontended-mutex variant
  exists specifically as a baseline, never as a stand-in for the
  contended cases — a lock's uncontended cost tells you almost nothing
  about its behavior under contention.
- **Oversubscribing one language differently.** Both languages' worker
  counts come from the same shared fixture; the benchmark matrix never
  runs Java at one thread count and Rust at another and compares them.
- **Busy spinning on shared CI hosts.** A pure-spin wait strategy on a
  host where other processes also need the CPU actively harms unrelated
  work — this lab's spin-then-park variant and its bounded spin limit
  exist partly because unbounded spinning is not a neutral choice on a
  shared machine.
- **Ignoring priority inversion/fairness.** A throughput number can look
  identical whether every worker got a fair share of the lock or one
  worker dominated — this lab's correctness oracle checks the exact total
  count, but fairness itself (who got the lock how often, in what order)
  requires the per-run occupancy/park evidence this lab's harnesses
  report, not aggregate throughput.

## Assumptions and scope

- Correctness for every variant is: the shared counter's final value
  equals exactly `workerCount × opsPerWorker` — this holds regardless of
  wait strategy or critical-section length, by construction.
- The "long critical section" variant's extra cost comes from a
  deterministic, side-effect-free busy-work function
  (`busyWork`/`busy_work`), not from I/O or allocation — isolating the
  "holding the lock longer" variable from unrelated costs.
- The shipped harnesses wire the **shared-counter** dataset only; the
  proposal's "small map update" and "handoff flag" dataset points are not
  yet parameterized in this iteration — the same kind of disclosed scope
  gap as the [MPSC Queues and Producer Contention](/lab/mpsc-contention/)
  lab's skewed/burst datasets, not fabricated coverage.
- `perf sched`, `perf stat` and async-profiler lock evidence are
  capability-detected; where unavailable on the native-Linux evidence
  host, this lab records that fact explicitly rather than substituting a
  different tool's output.
- The spin-then-park lock is a teaching implementation (a plain queue for
  waiters, tolerant of occasional spurious `unpark` calls), not a
  production-grade lock — see java.md/rust.md for the exact simplification
  and why it does not affect correctness.

## Pre-lab diagnostic — answer

The critical section's length relative to contention level is exactly
the variable that flips the outcome. Under low contention, a CAS retry
loop rarely fails, so it pays almost nothing beyond the one successful
atomic operation — cheaper than a mutex's lock/unlock protocol overhead
even though both do "the same amount of real work" per success. Under
high contention with many threads hammering the same location, every CAS
failure is a *wasted* attempt: the thread reads the current value, tries
to swap, fails, and must immediately retry — and the more threads
retrying simultaneously, the more of these wasted attempts pile up,
because there is no mechanism telling a failed thread to back off or wait
its turn; it just spins immediately back into the race. A blocked mutex
waiter, by contrast, is descheduled and consumes nothing while it waits,
and the lock hands off to exactly one waiter at a time rather than
letting many threads race for the same cache line simultaneously. This is
this lab's own contention-vs-mechanism crossover: CAS loops are excellent
when contention is low or the critical section (the amount of work being
raced over) is tiny, and can become actively worse than blocking once
both contention and effective per-attempt cost rise together.
