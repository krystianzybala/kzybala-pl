# Arena lifetimes, pools and reuse — Java track

Package `pl.kzybala.lab.arenareuse`: `ArenaReuseFixtures` (deterministic
source-data generation), `MessageBatchOperations` /
`ParseTreeOperations` / `ScratchBufferOperations` (five lifecycle
variants each), and `UnboundedPool<T>` / `BoundedPool<T>` (the two
reusable pool implementations every dataset's `globalPool`/`boundedPool`
variants share).

## The pieces

- **`allocatePerItem`** — a fresh scratch object/array/buffer every
  time, discarded immediately.
- **`batchArena`** — one `Arena.ofConfined()` per batch (500 messages /
  100 documents / 200 buffer-ops), records bump-allocated into one
  `MemorySegment` via the same offset-based technique
  content/labs/ffm-memory-segments established, then the WHOLE arena
  `close()`s at batch end — one measurable reset cost per batch, not per
  item.
- **`threadLocalReuse`** — one scratch instance per thread
  (`ThreadLocal.withInitial`), reused for every item for the life of the
  thread; never explicitly released.
- **`globalPool`** — `UnboundedPool<T>`, backed by
  `ConcurrentLinkedDeque`: `borrow()` polls a cached instance or
  allocates fresh; `release()` always pushes back, with no size limit —
  this lab's "unbounded pools" trap, implemented literally.
- **`boundedPool`** — `BoundedPool<T>`, backed by `ArrayBlockingQueue`
  with a fixed capacity (64): `release()` silently drops the instance
  once the queue is full, bounding memory regardless of burst size.

## A real, humbling finding from development wiring (dev-only, never published)

Running `-prof gc` against `messageBatches`' `fullPass` benchmark on this
repository's development machine produced this lab's single most
important result, and it directly contradicts the intuition that
"pooling reduces allocation": **`allocatePerItem` allocated essentially
nothing** — `gc.alloc.rate.norm` measured **1.6 B/op** for a full
500,000-message pass, meaning the JIT scalar-replaced nearly every
`MessageScratch` allocation away entirely (content/labs/escape-analysis-scalar-replacement's
mechanism, confirmed empirically here, not assumed). `threadLocalReuse`
matched it almost exactly (235,212 ns vs. `allocatePerItem`'s 236,333
ns) — there was nothing left to save by reusing. **`globalPool`, by
contrast, allocated 12,000,135 B/op** — roughly 12 MB per full pass —
because `ConcurrentLinkedDeque` allocates an internal linked-list node
on every `push`/`poll`, and those nodes are themselves real, GC-tracked
allocations that the tiny pooled object's own scalar-replacement
opportunity can never offset. The practical consequence: `globalPool`
(≈19.65 ms/pass) was roughly **83× slower** than `threadLocalReuse`
(≈235 μs/pass). `boundedPool` avoided the linked-node allocation problem
(`gc.alloc.rate.norm` ≈ 87 B/op, near zero) but was still **≈53× slower**
than `threadLocalReuse` (≈12.54 ms/pass) — `ArrayBlockingQueue`'s lock
acquisition on every `borrow`/`release` is a real, measurable tax even
with a single thread and zero contention. `batchArena` sat between the
extremes (≈859 μs/pass, ≈168,006 B/op, ≈47 GC episodes) — a real,
non-trivial but bounded and predictable cost, exactly as its "explicit
lifetime, explicit cost" design promises. The `oneBatchLatency`
(SampleTime) percentiles told the same story at the single-batch scale:
`threadLocalReuse`'s p99 was 292 ns; `boundedPool`'s was 13,616 ns;
`globalPool`'s was 22,688 ns, with a p999 of 91,930 ns. None of these
exact numbers are published evidence; the directions and the mechanism
(scalar replacement means there is nothing for a naive pool to save, and
the pool's own implementation can cost more than what it pools) are what
benchmark.md's real evidence is checked against.

## Build, correctness gate, run

```bash
cd content/labs/arena-lifetimes-reuse/code/java

# correctness gate — every variant sums to the identical total per dataset
mvn test

# build
mvn -q -DskipTests package

# dev/wiring JMH: messageBatches (unpinned)
java -jar target/benchmarks.jar ArenaReuseBenchmark

# same, with real allocation-rate evidence
java -jar target/benchmarks.jar ArenaReuseBenchmark.fullPass -p variant=globalPool -prof gc

# THE full-matrix publication benchmarks (pinned, via the native-Linux runner)
java -jar target/benchmarks.jar ArenaReuseLinuxEvidenceBenchmark \
  -p variant=boundedPool -p dataset=temporaryParseTrees
```

## Reading the results

- Always check `-prof gc`'s `gc.alloc.rate.norm` for `allocatePerItem`
  BEFORE evaluating any pooling variant — if it is already near zero,
  this lab's own evidence shows pooling is likely to make things worse,
  not better.
- Compare `globalPool` against `boundedPool` specifically to separate
  two distinct costs: `globalPool`'s extra allocation (from its linked
  structure) versus the lock overhead both share — `boundedPool`
  isolates the lock cost alone.
- Read `oneBatchLatency`'s full percentile spread, not just the mean —
  `batchArena`'s p999/max reveal the arena-close cost as a real,
  occasional latency spike that `fullPass`'s amortized average hides.
- `threadLocalReuse` winning outright here is a real result specific to
  this lab's tiny, single-threaded scratch objects — it is not a
  universal claim that pooling is always wrong (theory.md's assumptions
  section).
