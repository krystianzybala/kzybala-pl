# CAS contention and backoff — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the retry loop that "never fails")

A colleague replaces a hot `AtomicLong.incrementAndGet()` with this,
reasoning that "CAS only costs something when it fails, and ours almost
never fails":

```java
long next;
do {
    next = counter.get() + 1;
} while (!counter.compareAndSet(next - 1, next));
```

Under a 40-thread load test, throughput drops ~10× versus the same code at
4 threads, yet their dashboard shows a CAS *failure rate* of only ~8%.
They conclude the slowdown must be elsewhere — "92% of CASes succeed on
the first try."

**Task:** explain why a low failure rate does not imply low contention
cost, and name at least two costs this loop pays on every *successful*
CAS under contention.

**Success criteria:** your explanation involves the cache line's ownership
transfer (not just retries), you can say why the `get()` itself is not
free under contention, and you can predict what a fixed 1 µs backoff after
each failure would and wouldn't fix here.

<details>
<summary>Hint</summary>

Who owns the cache line when this thread's `compareAndSet` starts, in the
common case where 39 other threads are hammering the same counter? What
must the hardware do before the CAS can even attempt to execute — and does
that step depend on whether the CAS then succeeds?

</details>

<details>
<summary>Solution</summary>

A successful CAS under contention still pays the dominant cost: the line
must be brought into this core's cache in exclusive state (read-for-
ownership + invalidation of every other copy) *before* the CAS executes.
With 40 contenders, nearly every operation — successful or not — starts
with a cross-core line transfer; the ~92% success rate only says retries
are rare, not that transfers are. The initial `counter.get()` also pulls
the line (shared state), which is immediately yanked away again — under
heavy contention the get/CAS pair can cause two transfers per increment.
A fixed backoff after *failures* barely helps precisely because failures
are rare here: the traffic comes from successful operations queueing on
line ownership. Fixes that address the actual mechanism: shard the counter
(per-thread ownership + reduction, `LongAdder`-style) or batch increments
thread-locally.

</details>

## Exercise 2 — Implementation (add backoff without destroying latency)

The lab's Java project (`code/java/`) includes the no-backoff CAS counter
and benchmark harness.

**Task:** implement exponential backoff *with jitter* for the retry loop
(cap it — e.g. start ~1 µs, double per failed attempt, cap ~64 µs, add
uniform jitter), and measure at 1, 2, 4 and 8 threads.

**Success criteria (measure, don't assert):**

1. Correctness: the counter's final value still equals exactly
   `threads × incrementsPerThread` (the existing test must pass on your
   variant).
2. At 8 threads, your backoff variant's aggregate throughput is measurably
   above the no-backoff variant's on your machine.
3. At 1 thread, your variant is within noise of the no-backoff variant —
   backoff code that slows down the uncontended path failed the exercise.
4. You record per-operation latency, not just throughput, and can state
   the latency price the throughput win cost you.

<details>
<summary>Hint</summary>

Back off only after a *failed* CAS, never before the first attempt —
criterion 3 is testing exactly that. `ThreadLocalRandom` for jitter;
`LockSupport.parkNanos` or `Thread.onSpinWait()` loops for the wait, and
note which one you chose and why (parking hands the core away; spinning
holds it — different trade-off at different contention levels).

</details>
