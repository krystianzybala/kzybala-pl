# Arena lifetimes, pools and reuse — exercises

## Exercise 1 (diagnosis): the pool that made it worse

A team adds an object pool for a small (3-field) per-request scratch
object, expecting reduced GC pressure. After deploying, p99 latency
gets WORSE and `-prof gc`-equivalent monitoring shows allocation rate
barely changed. Using this lab's mechanism, name the most likely
explanation, and the exact sequence of two checks you would run to
confirm it (not guess it).

**Success criteria:** you name the same root cause this lab's own
Java-track evidence demonstrates (java.md): the original scratch object
was small enough that the JIT was likely already scalar-replacing it
(explaining why allocation rate barely changed pre/post pool — there was
little to change), while the pool itself added synchronization (and,
if built on a linked structure, its own allocation) that a
zero-allocation baseline had no way to offset; you name the two checks
in order — (1) run an allocation profiler against the ORIGINAL,
unpooled code first, to establish whether there was real allocation
pressure to relieve at all, and (2) if allocation was already low, run
an allocation profiler against the POOL implementation itself to check
whether it is allocating internally (a linked-list-backed pool will
show this directly, matching this lab's own `globalPool` finding).

<details>
<summary>Hint</summary>

This lab's own `theory.md` names the diagnostic order explicitly: check
the baseline BEFORE writing any pooling code. What would checking the
baseline FIRST have told this team before they ever deployed?
</details>

## Exercise 2 (implementation): find the object-size crossover

Using this lab's Java `MessageBatchOperations`, create a larger scratch
object (e.g., `MessageScratch` with 8 additional unused `long` fields,
making it clearly too large for the JIT to comfortably scalar-replace)
and re-run `allocatePerItem` vs. `threadLocalReuse` vs. `boundedPool`
with `-prof gc` (dev machine, wiring-only — do not publish these
numbers). Predict, before measuring, whether `boundedPool` can ever
outperform `threadLocalReuse` for a LARGE enough scratch object, and
why.

**Success criteria:** you implement the larger scratch object correctly
(checksum must still match the fixture, ignoring the new unused fields);
you state a specific, falsifiable prediction (e.g., "the crossover
depends on whether the larger object's real allocation cost exceeds the
pool's fixed lock-acquisition cost — `boundedPool` should close the gap
as object size grows, but `threadLocalReuse` has no allocation cost at
any size, so it should still win at every size tested" — or a reasoned
alternative); you measure and report the actual `-prof gc` numbers for
all three variants at the larger size; and you connect the result back
to this lab's mechanism explicitly.

<details>
<summary>Hint</summary>

`threadLocalReuse` never allocates per item regardless of scratch-object
size — only `allocatePerItem`'s cost scales with object size in a way a
pool could plausibly compete with. Is `threadLocalReuse` even a fair
baseline for this specific question, or is the real comparison
`allocatePerItem` vs. `boundedPool`?
</details>

<details>
<summary>Solution</summary>

There is no universally correct crossover independent of the measured
host and JDK build, but the mechanism predicts: `threadLocalReuse`
should continue to win regardless of object size, since it never
allocates per item at any size — it is not the right comparison for
finding a "pooling helps" crossover at all. The more meaningful question
is whether `boundedPool` can beat `allocatePerItem` once the object is
large enough that scalar replacement becomes unlikely (typically once a
type has enough fields, or contains a reference type, that the JIT's
escape-analysis heuristics decline to attempt replacement) — if your
measurement shows `boundedPool` closing the gap with, or beating,
`allocatePerItem` at the larger size while still losing to
`threadLocalReuse`, that is fully consistent with this lab's mechanism:
pooling can help once there is real allocation to save, but reuse
without pooling's synchronization overhead is still the better answer
whenever it is structurally available (as it is for every dataset in
this lab).
</details>

## Exercise 3 (evidence interpretation): read the percentile, not the mean

Below is the shape of one run's `oneBatchLatency` output for
`batchArena` on `messageBatches` (illustrative structure, not real
captured evidence):

```text
Benchmark                                (variant)  Mode  Cnt      Score    Units
oneBatchLatency                         batchArena  sample   980    620.4   ns/op
oneBatchLatency:p0.50                   batchArena  sample           590.0  ns/op
oneBatchLatency:p0.90                   batchArena  sample           650.0  ns/op
oneBatchLatency:p0.99                   batchArena  sample          1450.0  ns/op
oneBatchLatency:p0.999                  batchArena  sample         38200.0  ns/op
oneBatchLatency:p1.00                   batchArena  sample        410000.0  ns/op
```

Answer from this block alone: (a) does the mean (620.4 ns/op) fairly
represent a typical batch's cost, given the p50 is 590.0 ns/op; (b) what
does the enormous gap between p0.99 (1,450 ns) and p1.00 (410,000 ns —
roughly 280× the p99) most likely indicate about `batchArena`'s
reset/close cost specifically; (c) name one reason averaging this
variant's cost across many batches (as `fullPass` does) could hide a
real operational risk that `oneBatchLatency`'s percentiles expose.

**Success criteria:** (a) roughly yes for the median case — mean and p50
are close, meaning MOST batches cost close to the mean; (b) the p999/max
gap suggests `Arena.close()` (or GC/JIT interference around it) is
occasionally MUCH more expensive than usual — a real, if rare, tail
event consistent with this lab's own finding that arena close is a
genuine cost concentrated at batch boundaries, worth investigating with
`async-profiler` or JFR rather than dismissed as noise; (c) `fullPass`'s
single averaged number would completely hide a service that occasionally
stalls for 410 μs once every ~1,000 batches — for a latency-sensitive
system, THAT rare stall (not the typical 590 ns) could be the operational
risk that actually matters, exactly the reasoning behind this lab's
percentile-first requirement (theory.md, java.md).

<details>
<summary>Hint</summary>

This lab's own real `oneBatchLatency` evidence for `batchArena` showed a
similarly enormous gap between p999 and the max — was that dismissed as
measurement noise in java.md, or investigated as a real signal?
</details>
