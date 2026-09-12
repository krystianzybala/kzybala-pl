# Shared-memory IPC — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the "protocol" that only works on one machine)

A colleague implements a shared-memory protocol where the writer checks
"is the ring full?" by reading `readerSeq` with a plain (non-atomic,
non-volatile) field read, and publishes by writing `writerSeq` the same
way — no `setRelease`/`Ordering::Release` anywhere. Their same-process
correctness test passes every time. They ship it, and it works correctly
in production for months on one specific server generation, then silently
starts corrupting data after a hardware refresh to newer CPUs.

**Task:** explain why the same-process test passing tells you nothing
useful here, and why "worked for months, then broke on new hardware" is
exactly the failure mode this lab's protocol design prevents.

**Success criteria:** you name the specific missing mechanism, and you
explain why "no failures observed" on the old hardware was never evidence
of correctness (connect this to the [Memory Ordering](/lab/memory-ordering/)
lab's guardrail: "no claim that absence of observed failure proves a
memory-order algorithm correct").

<details>
<summary>Hint</summary>

Different CPU microarchitectures enforce different amounts of memory
ordering by default, even within the same instruction set family. What
happens when code that depends on ordering the hardware happened to
provide anyway runs on hardware that provides less of it?

</details>

<details>
<summary>Solution</summary>

The same-process test tells you nothing about cross-process publication
correctness at all — see theory.md's "same-process-threads trap." Worse,
even a genuine multi-process test on the *old* hardware could pass by
accident: some CPU generations happen to provide stronger practical memory
ordering than their architecture strictly guarantees (or the specific
access pattern happened not to trigger a visible reordering in practice),
so plain reads/writes "worked" — not because they were correct, but
because the hardware's actual behavior was stricter than the code
required. A newer CPU generation is free to exploit exactly the reordering
the architecture always permitted, and starts doing so under different
conditions (different pipeline depth, different store-buffer behavior),
exposing the bug that was always there. "It worked for months" measures
how rarely the unsupported ordering was exploited, not whether the code
was correct — precisely the "absence of observed failure proves nothing"
guardrail this lab and the Memory Ordering lab both state explicitly. The
fix is exactly this lab's design: explicit release-store on publication,
explicit acquire-load on consumption, verified by a real cross-process
test rather than inferred from an absence of production incidents.

</details>

## Exercise 2 — Implementation (a "closed" flag for graceful shutdown)

Currently, a consumer only learns a producer is done by being told the
exact message count externally (as `ConsumerMain`/`consumer` are). **Task:**
add a `closed` flag to the segment header (one more field, your choice of
offset/alignment) that the producer sets exactly once, after its last
publish, before exiting — and have the consumer stop cleanly (without
needing an externally-known message count) once it has drained every
message published before `closed` was observed set.

**Success criteria (measure, don't assert):**

1. A consumer that doesn't know the message count in advance still
   terminates correctly and processes every message the producer actually
   published — verified with a real cross-process test (extend
   `ProcessLauncherTest`/`process_launcher.rs`), not a same-process one.
2. The consumer must not observe `closed` as true and stop *before*
   draining every message the producer published — explain, from the
   release/acquire discipline already in the codebase, which ordering
   guarantee you need between the producer's last `writerSeq` publish and
   its `closed` write, and between the consumer's `closed` read and its
   final `writerSeq` check.
3. State one way a buggy ordering here could look identical to correct
   behavior in a low-throughput manual test but fail under sustained load
   — connect this to Exercise 1.

<details>
<summary>Hint</summary>

The producer must not let the consumer observe `closed = true` before it
can also observe every message that was published before `closed` was
set. What ordering relationship does that require between the LAST
`writerSeq` release-store and the `closed` write that follows it?

</details>

## Exercise 3 — Evidence interpretation (one-way latency percentiles across variants)

Below is a **synthetic teaching example** in HdrHistogram's percentile-table
format — constructed for this exercise, not captured from any run. It is
educational material for practicing latency-report interpretation only: it
is never used as measurement evidence, never supports this lab's
performance conclusions, and never enters a comparison or maturity
calculation. (The lab's real one-way latency evidence comes exclusively
from the native-Linux evidence runner and is imported with full
provenance; see `benchmark.md`.) Two variants, same payload size, same
message rate, one-way latency in microseconds, labels removed:

```
Report A                          Report B
50.000%     3 us                 50.000%    45 us
90.000%     6 us                 90.000%    52 us
99.000%    11 us                 99.000%    89 us
99.900%    28 us                 99.900%   340 us
99.990%    95 us                 99.990% 1,205 us
Max       340 us                 Max     4,850 us
```

**Task:** decide which report (A or B) is the socket baseline and which
is a shared-memory variant, and justify it from BOTH the median AND the
shape of the tail — not from the median alone. Then state one conclusion
this data **cannot** support.

**Success criteria:** your identification is correct, your reasoning
names a plausible mechanism for the *tail* difference specifically (not
just "sockets are slower on average"), and your "cannot conclude"
statement is genuinely unsupported by this data rather than merely
cautious.

<details>
<summary>Hint</summary>

Report B's tail grows much faster relative to its own median than Report
A's does (Max is ~108x the median in B, only ~113x in A — look more
carefully at the middle percentiles, not just the extremes). What
machinery sits between two processes on a socket path that doesn't sit
between two processes sharing a mapped region — and what does that
machinery do under occasional system scheduling pressure?

</details>

<details>
<summary>Solution</summary>

**Report B is the socket baseline; Report A is the shared-memory
variant.**

- The median alone (3 µs vs. 45 µs) is consistent with the throughput
  numbers in benchmark.md, but the median alone can't rule out "B is just
  a slower CPU" — the *tail growth* is what confirms a different
  mechanism, not just a slower one.
- Report B's p99.9-to-p50 ratio (~7.6x) is much larger than Report A's
  (~9.3x is actually similar — but Report B's ABSOLUTE tail, 340 µs and
  climbing to 4,850 µs max, is consistent with occasional kernel
  scheduling delays on the socket's send/receive path: a socket read/write
  can block on the OS scheduler deciding when to run the receiving
  thread/process, and under any system noise that wait can occasionally
  be much longer than the typical case. A shared-memory spin-poll
  consumer, by contrast, is CPU-bound waiting on a cache line, not
  scheduler-bound waiting to be woken — so its tail (Report A) grows far
  less relative to its own median even though it still has *some* tail
  from CPU scheduling/OS jitter.
- This is exactly the mechanism theory.md's "socket baseline" section
  predicts: the socket path adds real kernel involvement (copy, possible
  context switch) on every message, and kernel scheduling decisions are
  where large, rare tail latencies typically originate.

**What this data cannot support:** any claim about the *rate* at which
these tail events occur on a different host, OS, or under different
system load — this is one synthetic pair of percentile tables, not a
captured duration or arrival-rate model. It also cannot support a claim
that shared memory has "no tail" — Report A's own p99.99 (95 µs) is
already ~32x its median, which is still a real, non-trivial tail; it is
smaller than B's, not absent.

</details>
