# UDP ingest, batching and packet loss — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the receiver whose queue can't be full)

A colleague builds a copying-handoff pipeline with an
`ArrayBlockingQueue` sized "generously" at 1,000,000 entries, and reports
"we never see application-level drops — our pipeline is lossless." Under
sustained overload in production, their process eventually crashes with
an out-of-memory error.

**Task:** explain why "we never see drops" and "our process crashed under
load" are the same bug, not two unrelated facts.

**Success criteria:** you connect this directly to the "unbounded queues
hiding loss" trap, and you state what a correctly-sized bounded queue
would have reported instead of a crash.

<details>
<summary>Hint</summary>

A queue sized at 1,000,000 entries isn't infinite, but is it meaningfully
*bounded* relative to what "overload" means for this system? What
happens to queueing latency for the millionth item, long before the
queue is actually full?

</details>

<details>
<summary>Solution</summary>

A queue that's merely *large* rather than deliberately *bounded to the
system's actual processing capacity* doesn't eliminate the drop — it
defers it and disguises it as unbounded memory growth. Under sustained
overload, arrivals outpace drains indefinitely; the queue doesn't reject
anything until it (eventually) exhausts available memory, at which point
the failure mode is a crash, not a countable, disclosed drop. Worse,
long before that crash, every item sitting behind a million queued
predecessors is experiencing enormous queueing latency that "0 drops"
completely hides — the system has effectively failed its latency budget
long before it fails outright. A queue sized to the pipeline's actual
sustainable throughput would instead report a rising, explicit
`applicationDropped` count the moment arrival rate exceeds drain rate —
the honest signal this lab's design insists on.

</details>

## Exercise 2 — Implementation (an adaptive slab size)

**Task:** implement a variant of the zero-copy handoff pipeline whose
slab size adapts: start small (e.g. 8 slots), and when the receiver
observes `applicationDropped` incrementing at more than some rate
threshold over a recent window, grow the slab (allocate a larger one,
migrate any still-in-flight indices).

**Success criteria (measure, don't assert):**

1. Correctness: every message is still accounted for exactly once
   (delivered, dropped, or corrupted) through a slab resize event,
   verified with a test that forces at least one resize mid-run.
2. Measure whether growing the slab actually reduces the drop rate for a
   given burst profile, or whether the bottleneck is elsewhere (e.g. the
   consumer thread's own processing rate, which a bigger slab cannot fix).
3. State one scenario where growing the slab makes tail latency *worse*
   rather than better — connect this to theory.md's "batching amortizes
   syscalls but increases queueing latency" hypothesis half.

<details>
<summary>Hint</summary>

A bigger slab means more datagrams *can* be outstanding before a drop —
but if the consumer is the actual bottleneck (not the slab size), what
happens to the *age* of the oldest still-unprocessed message as the slab
grows?

</details>

## Exercise 3 — Evidence interpretation (kernel vs. application drop counters)

Below is a **synthetic teaching example** in `ss -u -a` / `/proc/net/udp`
style output — constructed for this exercise, not captured from any run.
It is educational material for practicing counter interpretation only: it
is never used as measurement evidence, never supports this lab's
performance conclusions, and never enters a comparison or maturity
calculation. (The lab's real drop-rate evidence comes exclusively from
the native-Linux evidence runner and is imported with full provenance;
see `benchmark.md`.) Two runs of the same ingest pipeline under the same
overload profile, labels removed:

```
Run A:
  Application accounting:  delivered=48,204  applicationDropped=1,796  corrupted=0
  Kernel counter (drops):  312

Run B:
  Application accounting:  delivered=49,988  applicationDropped=12     corrupted=0
  Kernel counter (drops):  9,847
```

**Task:** decide which run has a receive loop that is falling behind the
kernel's own receive-buffer draining (i.e., the application isn't calling
`receive()` fast enough), and which has a receive loop that keeps up with
the kernel but has an undersized bounded queue/slab. Justify from BOTH
counters together, not either alone. Then state one conclusion this data
**cannot** support.

**Success criteria:** your identification is correct, your reasoning
explains why kernel drops and application drops are evidence of two
*different* bottlenecks, and your "cannot conclude" statement is
genuinely unsupported by this data rather than merely cautious.

<details>
<summary>Hint</summary>

A kernel drop happens before the application ever sees the datagram; an
application drop happens after the application has already successfully
received it. Which run's kernel counter suggests the application itself
isn't draining the socket fast enough to prevent buffer overflow at the
OS level?

</details>

<details>
<summary>Solution</summary>

**Run B has the receive loop falling behind the kernel; Run A has an
undersized bounded queue/slab with a receive loop that mostly keeps up.**

- Run B's kernel-level drop count (9,847) is nearly 100x Run A's (312) —
  this is evidence accumulating *before* the application ever calls
  `receive()`, which only happens when the OS's own socket receive buffer
  fills faster than the application drains it. A receive loop with a slow
  per-call cost (or one blocked doing something else, like a full,
  blocking handoff) is the classic cause.
- Run A's application-level drop count (1,796) is much higher than Run
  B's (12), while its kernel drops are much lower — this application is
  *pulling data off the socket successfully* (low kernel drops) but its
  downstream bounded path is too small for the arrival rate, rejecting a
  meaningful fraction after successfully receiving it.
- The two counters together, not either alone, are what makes the
  diagnosis possible: a high application-drop count with a low kernel-drop
  count means "receive loop is fine, downstream capacity is the
  bottleneck"; a high kernel-drop count means "the receive loop itself
  isn't draining the socket fast enough," regardless of what the
  downstream bounded path's own numbers say.

**What this data cannot support:** any claim about which specific line of
code in either run is responsible, or what the *right* queue/slab size or
receive-loop optimization would be — this is one synthetic snapshot of
two counters, not a profiler trace or a capacity-planning model. It also
cannot be used to compare Java and Rust implementations, since nothing
here identifies which language (if either) produced these numbers.

</details>
