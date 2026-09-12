# Safepoints and time to safepoint — exercises

## Exercise 1 (diagnosis): the pause that grew with headcount

A service's p99 GC pause was stable for months at ~15 ms. After scaling
the thread pool from 8 to 64 threads to handle more concurrent requests
(no other change — same heap, same collector, same workload per
request), p99 pause climbs to ~90 ms, though GC log entries still show
the collection itself taking about the same few milliseconds. Diagnose
what changed, using this lab's mechanism, and name the one metric that
would confirm your diagnosis directly rather than by inference.

**Success criteria:** you identify that adding 56 more JVM threads adds
56 more participants to every future safepoint's handshake, and that
this cost scales with thread count independent of what those threads are
doing — consistent with this lab's `manyIdleThreads` finding that even
non-busy threads carry a real per-thread coordination tax; you state that
the GC log's own collection-time entries are the WRONG evidence to
inspect for this (they answer "was the operation slow," not "was
reaching it slow"); and you name TTSP specifically (via
`-Xlog:safepoint` or JFR `jdk.SafepointBegin`, this lab's own evidence
tool) as the metric that would directly confirm thread count is the
driver, rather than inferring it from the pause total alone.

<details>
<summary>Hint</summary>

The collection itself staying constant while total pause grows is
exactly this lab's decomposition: application stopped time = TTSP +
operation + resume. If operation time is flat, which term is left to
explain the growth?
</details>

## Exercise 2 (implementation): find your own idle-thread threshold

Using this lab's Java `SafepointHarness`, change `IDLE_THREAD_COUNT`
from 300 to a smaller value (e.g., 20) and rerun `manyIdleThreads` on
`numericLoop` (dev machine, wiring-only — do not publish these numbers).
Predict, before rerunning, whether TTSP's p99 will still show a
noticeable spike compared to the `cooperativeLoop` control.

**Success criteria:** you state a specific, falsifiable prediction (e.g.,
"the per-thread cost is small enough that 20 extra threads should barely
move p99, while 300 clearly did" — or the opposite, with reasoning); you
measure and report the actual result; and you connect it back to this
lab's mechanism — per-thread handshake cost is usually small individually
and becomes visible only in aggregate, so there is likely some threshold
count below which it is lost in ordinary measurement noise. Revert
`IDLE_THREAD_COUNT` to `300` afterward; it is not part of the lab's
fixture contract (though changing it does not affect the correctness
checksum, since idle threads never contribute to the checksum).

<details>
<summary>Hint</summary>

This lab's own development run showed 300 idle threads producing a
p99/max nearly 50× the control's p99. Is that ratio likely to scale
linearly down to 20 threads, or does it more plausibly reflect some
per-thread cost that only becomes visible past a certain count?
</details>

<details>
<summary>Solution</summary>

There is no single universally-correct threshold — it depends on the
measured JVM build and host — but the general shape to expect is
sub-linear-then-visible: a handful of extra idle threads typically adds a
cost too small to distinguish from measurement noise, while a few hundred
makes the per-thread handshake tax dominate p99 clearly. If your 20-
thread run shows no measurable difference from the control, that is a
valid, useful finding — it tells you the per-thread cost on your specific
host is small enough that dozens of idle threads do not matter, which is
itself worth knowing before assuming thread count is always a lever
worth pulling.
</details>

## Exercise 3 (evidence interpretation): read the episode, not the average

Below is the shape of one run's captured episode data from this lab's
publication runner (illustrative structure, not real captured evidence):

```text
variant=threadInNativeCall dataset=numericLoop
  episode 1: ttspNanos=3200  opNanos=5800000  endNanos=900  totalThreadCount=16
  episode 2: ttspNanos=3400  opNanos=5750000  endNanos=850  totalThreadCount=16
  episode 3: ttspNanos=185000 opNanos=5900000  endNanos=920  totalThreadCount=16
```

Answer from this block alone: (a) does episode 3's much larger
`ttspNanos` (185,000 vs. ~3,300 for the other two) mean the native-blocked
thread finally "caught up" and slowed the safepoint down, contrary to
this lab's claim; (b) `totalThreadCount` is identical across all three
episodes — does that rule out thread-count growth as episode 3's cause;
(c) name one alternative, mundane explanation for a single elevated TTSP
among otherwise-consistent episodes that has nothing to do with this
lab's five named variants at all.

**Success criteria:** (a) not necessarily, and not by this lab's own
claim — a native-blocked thread is safepoint-safe and excluded from the
wait requirement regardless of which episode it is; the elevated episode
more likely reflects some *other* participating thread (the trigger
worker itself, or a JVM background thread) momentarily failing to reach
its poll point promptly, not the native thread "catching up"; (b) yes —
identical `totalThreadCount` across all three episodes rules out a
thread-count explanation specifically, since the participant set did not
change; (c) ordinary OS scheduling noise (the trigger worker's thread
being preempted right before a poll point, a page fault, a momentary
CPU frequency transition) is a mundane, common cause for a single
outlier episode among otherwise-consistent ones — exactly why "running
with uncontrolled OS scheduling" is a named trap and why publication
runs pin CPUs explicitly rather than trusting the OS scheduler's default
behavior.

<details>
<summary>Hint</summary>

This lab's own theory page states precisely what makes a thread "cost
nothing" to TTSP: being safepoint-safe already. A native-blocked thread
IS in that category from the moment it enters native code — nothing
about elapsed time changes that classification.
</details>
