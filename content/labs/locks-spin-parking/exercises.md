# Locks, spin waiting and parking — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the CAS loop that regressed under load)

A colleague replaces a `synchronized`/`Mutex`-protected counter increment
with a `compareAndSet`/`compare_exchange` retry loop, benchmarks it with
2 threads, sees a speedup, and ships it. In production, under 32
concurrent threads, throughput is *worse* than the mutex version it
replaced, and CPU usage is pinned at 100% on every core.

**Task:** explain what changed between the 2-thread test and the
32-thread production load that caused the reversal, and why "100% CPU
with worse throughput" is itself a diagnostic clue, not just a
coincidence.

**Success criteria:** you name the specific mechanism (not just "more
contention") — what happens to a CAS retry loop's *wasted work* as
thread count grows — and you explain why 100% CPU alongside falling
throughput specifically points at spinning/retrying rather than at, say,
I/O or allocation.

<details>
<summary>Hint</summary>

Every failed CAS attempt in this lab's `CasLoopKernel`/`cas_loop` module
is counted separately from successful ones. What does that counter do as
thread count rises, and what is every one of those failed attempts
actually consuming?

</details>

<details>
<summary>Solution</summary>

At 2 threads, a CAS attempt fails only when the other thread's own
successful CAS happened to land in the same tiny window — rare, so the
retry loop pays for almost nothing beyond genuine successful work. At 32
threads all racing the same location, most attempts fail: each thread's
`compare_exchange` reads a value that some other thread has already
changed by the time the CAS executes, forcing an immediate retry with no
useful work done. Every one of those failed attempts is 100% CPU spent
producing zero progress — which is exactly why CPU pins at 100% while
throughput falls: the CPU is fully busy, but an ever-larger fraction of
that busy time is wasted retries rather than successful increments. A
blocked mutex waiter, by contrast, is descheduled while another thread's
critical section runs, so it costs nothing during that window — this is
this lab's contention-vs-mechanism crossover made concrete.

</details>

## Exercise 2 — Implementation (add a bounded-spin-count metric to the mutex baseline)

This lab's spin-then-park hybrid reports `parkCount`/`park_count`, but
none of the pure-spin-adjacent variants report how much CPU time is
"wasted" waiting versus doing real work.

**Task:** using `SpinThenParkKernel`/`spin_then_park` as your starting
point, add a per-worker counter of *spin iterations actually consumed*
(not just whether a park eventually happened), and report the aggregate
across all workers alongside the existing correctness check.

**Success criteria (measure, don't assert):**

1. Correctness (`isCorrect`/`is_correct`) still holds exactly.
2. At `spinLimit = 0`, your new metric reports (near) zero spin
   iterations consumed (every waiter should go essentially straight to
   parking).
3. At a large `spinLimit` (e.g. 10,000) under the same worker count and
   op count, your metric shows meaningfully more spin iterations consumed
   than at the default `SPIN_LIMIT = 100` — and you can state, from that
   comparison, whether raising the spin limit reduced or increased the
   park count, and why.

<details>
<summary>Hint</summary>

The existing `spins` local variable inside `lock()`/`SpinParkLock::lock`
already counts exactly this per call — the only change needed is where
that count goes when it's about to be discarded.

</details>

## Exercise 3 — Evidence interpretation (reading context-switch counts against a wait-strategy claim)

Below is a **synthetic teaching example** — fabricated context-switch and
CPU-time figures, constructed for this exercise, not captured from any
real run. It is educational material for practicing evidence
interpretation only: it is never used as measurement evidence, never
supports this lab's performance conclusions, and never enters a
comparison or maturity calculation. (This lab's real CPU/context-switch
evidence comes exclusively from the native-Linux evidence runner's
`perf stat`/`perf sched` capture; see benchmark.md.)

```
4 workers, same shared-counter workload, same op count:

Strategy          Wall time (ms)   CPU time (ms, summed across cores)   Voluntary context switches
Pure spin              210                    830                              12
Spin-then-park          240                    290                           4,850
Pure park (spinLimit=0) 310                    140                          41,200
```

A teammate concludes: "spin-then-park is strictly worse than pure spin —
it's slower AND it has way more context switches, so spinning is just
better here."

**Task:** evaluate this conclusion using all three columns, not just wall
time and context-switch count. What does the CPU-time column add that
the other two don't, and does it change which strategy looks
preferable — and for what goal specifically?

**Success criteria:** you correctly compute and state that pure spin uses
roughly 2.9x more total CPU time than spin-then-park for a 30ms wall-time
difference, and you state the specific goal (e.g. "lowest latency
regardless of CPU cost" vs. "acceptable latency at minimum CPU cost on a
shared host") under which each strategy's data would actually make it the
right choice.

<details>
<summary>Hint</summary>

"Wall time" measures how long this one workload took. "CPU time" measures
how much of the machine's total capacity it consumed while doing so — on
a machine also running other work, those are answers to different
questions.

</details>

<details>
<summary>Solution</summary>

The teammate's conclusion conflates "faster" with "better" without
naming what's being optimized for. On wall time alone, pure spin does win
(210ms vs 240ms) — genuinely faster for *this* workload in isolation.
But the CPU-time column tells a different story: pure spin consumes 830ms
of CPU time across cores to finish in 210ms wall time, while spin-then-
park consumes only 290ms of CPU time to finish in 240ms — pure spin uses
roughly 2.9x more total CPU for a 30ms (about 14%) wall-time improvement.
On a dedicated machine where no other work competes for CPU, that
trade might be worth it — the 830ms of CPU time was never going to be
used for anything else anyway, so "wasted" spinning cost nothing real.
On a shared host (the exact "busy spinning on shared CI hosts" trap this
lab names), that same 830ms directly delays whatever *other* work was
waiting for those cores — pure spin's "win" there is a win against this
one benchmark's clock, paid for by every other process on the machine.
The context-switch count alone (12 vs 4,850) makes spin-then-park look
purely worse without this context; the CPU-time column is what actually
lets you connect that count to a real cost and decide which strategy
serves the actual goal.

</details>
