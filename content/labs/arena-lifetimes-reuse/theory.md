# Arena lifetimes, pools and reuse — theory

## Performance question and hypothesis

**Question:** can explicit lifetime regions eliminate allocation
pressure without becoming a manual-memory disaster?

**Hypothesis:** batch-scoped arenas and reusable buffers can make
lifetime cost predictable, but pooling may retain memory, increase
contention and complicate ownership.

**What would disprove it:** if every reuse/pooling strategy in this
lab's matrix were uniformly faster than allocating fresh every time, the
premise that reuse has its own real costs would be wrong. Every variant
reads the identical value stream and reproduces the identical checksum;
only how scratch memory is obtained, reused and released changes, and
this lab's real evidence (JMH/Criterion-measured, including actual
allocation-rate evidence via `-prof gc`, never estimated) is checked
against that.

## Learning objective

Model an operation's memory lifetime explicitly before choosing a
strategy for it, recognize the specific conditions under which pooling
is actively harmful rather than merely unnecessary, and build reuse
strategies with an explicit, bounded footprint.

## Prerequisites

- The [Escape analysis and scalar replacement](/lab/escape-analysis-scalar-replacement/)
  lab — this lab's central Java-track finding is only explicable in
  terms of that lab's mechanism (java.md).
- The [Heap vs off-heap with FFM MemorySegment](/lab/ffm-memory-segments/)
  lab — this lab's `batchArena` variant reuses `Arena.ofConfined()`
  directly.

## Pre-lab diagnostic

A team notices their message-processing service allocates a small
scratch object per message and decides to add an object pool to reduce
GC pressure, expecting a clear win. After deploying, throughput
DECREASES and heap usage, if anything, goes up. Using this lab's
mechanism, name the single most likely explanation, and the one profiler
metric you would check FIRST before writing a single line of pooling
code.

(Answer at the end of this page.)

## The mechanism: reuse only pays for a cost that is actually being paid

- **The JIT may already be eliminating the allocation you are trying to
  pool.** Escape analysis can prove a short-lived, non-escaping object
  never needs a real heap allocation at all — its fields become
  registers/stack slots instead (content/labs/escape-analysis-scalar-replacement).
  If that is already happening, "reuse" and "pool" have nothing left to
  save: this lab's own measured evidence shows exactly this for its
  `messageBatches` dataset's tiny scratch object (java.md).
- **A pool's OWN implementation can allocate more than the object it is
  pooling.** A naive concurrent pool backed by a linked structure
  (Java's `ConcurrentLinkedDeque`, for instance) allocates an internal
  node wrapper on every push and pop — if the pooled object is small and
  would otherwise have been scalar-replaced, the pool can end up
  allocating MORE bytes per operation than never pooling at all. This
  lab's own measured evidence shows this directly and dramatically
  (java.md): a real, humbling, non-hypothetical result.
- **Even a zero-allocation pool still pays a synchronization tax.** A
  capacity-bounded pool backed by a fixed array (Java's
  `ArrayBlockingQueue`, Rust's `Mutex<Vec<T>>`) avoids the linked-node
  allocation problem — but every `borrow`/`release` still acquires a
  lock, even with a single thread and zero contention. This lab's own
  evidence shows this cost alone can dwarf the cost of the "expensive"
  allocation it was meant to avoid.
- **A batch-scoped arena trades many small costs for one larger,
  measurable one.** Opening an `Arena.ofConfined()` per batch, writing
  the batch's records into it, then closing it, replaces N per-item
  allocations with one open, N writes and one close — the close is a
  real, non-trivial cost (this lab's `batchArena` variant measures it
  directly, both as an amortized B/op figure and as a distinct latency
  spike in the p99/p999 tail of `oneBatchLatency`), but it is a
  PREDICTABLE cost, occurring exactly once per batch rather than
  scattered unpredictably across GC cycles.
- **Thread-local reuse is often the best answer to a problem pooling was
  invented for.** If exactly one thread ever needs the scratch object
  at a time, and its lifetime never needs to outlive that thread, a
  single reused instance (Java `ThreadLocal`, a plain reused Rust local)
  gets the memory-pressure benefit pooling promises without paying for
  any cross-thread coordination at all — this lab's own evidence shows
  it winning outright in both languages, for different underlying
  reasons (java.md, rust.md).
- **Rust has no scalar replacement, so the SAME source pattern means a
  different thing.** `Box::new` in Rust is always a real `malloc` call;
  there is no JIT to prove it away. This means Rust's `allocatePerItem`
  and `threadLocalReuse` are NOT expected to converge the way Java's do
  — and this lab's own measured evidence confirms they do not (rust.md).
  Reading Java's finding onto Rust without checking would be exactly
  this lab's "assuming a mechanism transfers between languages" mistake.

## Visualization 1: lifetime-region diagram (deterministic)

Who owns the scratch memory and when it is reclaimed, per variant — not
a measurement, the exact contract this lab's implementation follows:

| Variant | Owner | Reclaimed |
|---|---|---|
| allocatePerItem | the item itself | as soon as it becomes unreachable (GC-eligible / dropped) |
| batchArena | the batch's `Arena` | explicitly, once, when the batch's arena closes |
| threadLocalReuse | the thread | never explicitly — lives for the thread's lifetime |
| globalPool / boundedPool | the shared pool | returned to the pool after each use, reclaimed only if the pool itself is dropped |

## Visualization 2: memory high-water timeline (illustrative pattern)

A generic sketch of retained memory over time — **illustrative of the
general shape, not extracted from a live run of this lab's code**; the
real evidence is this lab's own `-prof gc`/heap-profiler output
(java.md, benchmark.md):

```text
allocatePerItem:   [sawtooth, tiny amplitude — mostly scalar-replaced away]
batchArena:        [sawtooth, one tooth per batch, amplitude = one batch's worth]
threadLocalReuse:  [flat — one object's worth, forever]
globalPool:        [ratchets up under any burst, rarely comes back down]
boundedPool:       [flat, capped at capacity × object size]
```

## Visualization 3: throughput vs pool contention (conceptual model)

Why a pool's synchronization cost can dominate even without real
multi-thread contention:

| Cost source | Present in threadLocalReuse? | Present in globalPool/boundedPool? |
|---|---|---|
| Allocation (if not scalar-replaced) | once, amortized over the thread's life | every borrow that misses the cache |
| Lock/CAS on every access | no | yes, always — even single-threaded |
| Internal pool-structure allocation (if linked) | no | yes, on push/pop, for a naive linked pool |

Textual fallback for all three visualizations: reuse only saves a cost
that is genuinely being paid; a pool adds real synchronization and
sometimes real allocation overhead of its own, which must be measured
against what it claims to save, never assumed.

## Terminology

- **Escape analysis / scalar replacement** — the JIT optimization that
  can eliminate a non-escaping object's heap allocation entirely
  (content/labs/escape-analysis-scalar-replacement).
- **Arena** — an explicit lifetime/ownership boundary; closing it
  invalidates everything allocated from it in one operation.
- **Object pool** — a cache of pre-constructed instances, borrowed and
  returned rather than allocated and discarded.
- **Bounded pool** — a pool with a hard capacity limit; excess returns
  are dropped rather than retained.

## Assumptions and scope

- Every variant sums the identical deterministic checksum over the
  identical value stream regardless of lifecycle strategy (java.md,
  rust.md); B/op, allocations/op, reset/close cost and p99 are measured,
  never correctness-checked, because they are not deterministic
  quantities in the same sense.
- This lab's pool capacity (64) and batch sizes are stated explicitly
  and are lab parameters, not claims about a universally correct pool
  size for any real system.
- Rust's `allocatePerItem`/`threadLocalReuse` are NOT expected to
  converge the way Java's do — that divergence is this lab's central
  Rust-track finding (rust.md), not a gap to close.

## Pre-lab diagnostic — answer

The single most likely explanation is exactly this lab's own measured
finding: the scratch object was small enough that the JIT was already
scalar-replacing it, meaning there was no real allocation pressure to
relieve in the first place — the pool then added pure synchronization
(and, if built on a naive linked structure, real allocation of its OWN
internal nodes) on top of a baseline that was already close to free. The
one profiler metric to check FIRST, before writing any pooling code, is
JMH's `-prof gc` (or an equivalent allocation profiler) run against the
CURRENT, unpooled code — specifically `gc.alloc.rate.norm` (bytes
allocated per operation). If that number is already near zero, pooling
has nothing to gain and, per this lab's own evidence, a great deal to
lose.
