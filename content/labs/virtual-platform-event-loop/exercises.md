# Virtual Threads vs Platform Threads vs Event Loops — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the endpoint that got slower)

A team moves a request handler from a fixed platform-thread pool to
`Executors.newVirtualThreadPerTaskExecutor()`. Most endpoints get faster.
One endpoint — which does a database call, then holds a `synchronized`
lock around updating an in-memory cache while doing a second, slower
downstream call — gets *worse* under load once concurrency increases.

**Task:** explain the mechanism that could make virtual threads a
regression here rather than an improvement, and name the one change to the
code (not the executor) that would most directly fix it.

**Success criteria:** you name pinning (a virtual thread unable to unmount
while blocked inside the lock) as the candidate mechanism, explain why it
specifically bites under *load* (carrier starvation only shows up once
concurrent pinned tasks approach or exceed the carrier pool size) and
correctly identify "don't hold a lock across a blocking call" as the fix
that doesn't require reverting to platform threads.

<details>
<summary>Hint</summary>

Compare this to this lab's `lockNativePinningCase` dataset — what does it
put inside its `synchronized` block, and why does that specific ordering
matter?

</details>

<details>
<summary>Solution</summary>

If the JVM cannot unmount a virtual thread from its carrier while it is
blocked (the historical case: blocked inside a `synchronized` block, prior
to JEP 491's improvements), that virtual thread occupies its carrier for
the entire blocking duration — exactly like a platform thread would. Under
low concurrency this is invisible: there are more carriers than pinned
tasks, so nothing queues. Under load, once the number of concurrently
pinned tasks approaches the (small, core-sized) carrier pool, every
*other* virtual thread — including ones with no lock involvement at all —
starts queuing behind exhausted carriers, and the whole system's latency
degrades in a way that looks like a global regression, not a problem
localized to the one endpoint. The fix is not "add more carriers" (that
just moves the ceiling) or "go back to platform threads" (that gives up
the whole point) — it is restructuring the code so the slow downstream
call happens *outside* the lock: acquire the lock only for the in-memory
update, release it, then make the slow call. This is the same "reservation
before publication" discipline this site's other concurrency labs teach:
minimize what happens while holding exclusive access.

</details>

## Exercise 2 — Implementation (make the event loop safe for a long CPU stage)

The lab's `FixedEventLoopRunner`/`fixed_event_loop` runs every task's CPU
stage directly on the one loop thread — fine for `shortCpuStage`, but
`fixedEventLoop_longCpu` in benchmark.md shows exactly the stall this
causes.

**Task:** modify (a copy of) the event-loop variant so that the CPU stage
is offloaded to a small worker pool instead of running on the loop thread,
while the loop thread itself still only ever registers non-blocking timers
— i.e., build the "mixed" pattern for the event-loop case specifically,
not the virtual-thread case.

**Success criteria (measure, don't assert):**

1. The existing correctness tests still pass for your modified runner
   (same total checksum, same completed count, for every dataset).
2. On `longCpuStage`, your modified variant's throughput improves
   measurably over the unmodified `fixedEventLoop_longCpu` result on your
   machine — because the CPU work now runs on more than one thread.
3. You can explain why this doesn't turn the event loop into a CPU-bound
   pool: the loop thread still does the scheduling/dispatch, and still
   never blocks on I/O — only the actual CPU-bound checksum computation
   moves off it.

<details>
<summary>Hint</summary>

You already have the pattern: this lab's `mixed` variant does exactly this
handoff for virtual threads (submit the checksum to a separate pool,
`.get()` the result). Apply the same handoff from inside the event loop's
scheduled callback instead of from inside a virtual thread's task body.

</details>

## Exercise 3 — Evidence interpretation (pinning telemetry that isn't there)

Below is a **synthetic teaching example** — constructed for this exercise,
not captured from any run — modeled on the shape of JFR's
`jdk.VirtualThreadPinned` event count, one of the "pinning events" this
lab's design.md lists as a required metric. It is educational material for
practicing evidence interpretation only: it is never used as measurement
evidence, never supports this lab's performance conclusions, and never
enters a comparison or maturity calculation.

```
Run A (JDK 21, synchronized-wrapped blocking call):
  jdk.VirtualThreadPinned events: 1,842
  p99 end-to-end latency:         41.2 ms

Run B (JDK 26, identical code, identical load):
  jdk.VirtualThreadPinned events: 0
  p99 end-to-end latency:         3.4 ms
```

**Task:** these two runs used byte-for-byte identical application code and
identical load. State the single most likely explanation for the
difference, and then state one conclusion this pair of numbers **cannot**
support about virtual threads in general.

**Success criteria:** you correctly identify the JDK version change (JEP
491 landing in JDK 24, removing most `synchronized`-block pinning) as the
most likely explanation rather than anything about the application code,
and your "cannot conclude" statement recognizes that this pair says
nothing about pinning caused by mechanisms JEP 491 did *not* fix (certain
native/JNI blocking calls still pin).

<details>
<summary>Solution</summary>

The application code and load are identical, so the difference must come
from the one thing that changed: the JDK. JEP 491 (delivered in JDK 24)
removed pinning for most blocking operations performed while holding a
`synchronized` lock, which is exactly the shape this lab's
`lockNativePinningCase` dataset exercises — Run A's 1,842 pinning events
and much higher p99 are the pre-JEP-491 carrier-starvation signature this
lab's theory page describes; Run B on JDK 26 shows the fix. **What this
cannot support:** that virtual threads never pin on any JDK from here
forward — JEP 491 addressed `synchronized` specifically; blocking native
calls (certain JNI code, some file-I/O paths depending on OS) can still
pin a virtual thread to its carrier on current JDKs, and zero observed
pinning events in one synthetic pair proves nothing about those other
causes, which this dataset does not exercise at all.

</details>
