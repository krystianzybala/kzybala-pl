# MPSC queues and producer contention — theory

## Performance question and hypothesis

**Question:** what changes when many producers contend for one
consumer-owned stream?

**Hypothesis:** shared producer sequencing introduces CAS and cache-line
contention; batching, shards or per-producer queues can outperform a
single MPSC structure.

**What would disprove it:** if a single shared MPSC structure's
throughput did not degrade as producer count grew (no contention effect
to explain); if batching claims into larger chunks showed no reduction in
failed CAS attempts per item; or if the per-producer fan-in design (zero
shared structure) performed no better than the shared design under high
producer counts (contention isn't actually the bottleneck the theory
claims) — any of these would mean the contention model taught here is
wrong for this workload.

## Learning objective

Explain why "many writers, one reader" is a fundamentally different
problem from the single-producer/single-consumer case this lab's
prerequisite covers: show where the shared mutable state actually lives
(the claim point, not the queue slots themselves), contrast three
different ways to reduce contention at that point (batching, sharding,
mutual exclusion), and measure fairness and occupancy honestly rather
than only aggregate throughput.

## Prerequisites

- The [Memory Ordering: VarHandles and Rust Atomics](/lab/memory-ordering-atomics/)
  lab — this lab's shared-claim CAS loop and batched `fetch_add` claim
  are direct applications of the ordering vocabulary that lab
  establishes; understanding why a CAS can fail and retry is assumed.
- The [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab — the per-producer
  fan-in variant here is literally N independent instances of that
  lab's single-producer/single-consumer ring; the reservation/
  publication discipline is not re-derived here.

## Pre-lab diagnostic

A team replaces a single shared queue (all 8 producer threads push into
one `ConcurrentLinkedQueue`) with 8 separate per-thread queues that a
single consumer polls in round-robin. Throughput goes up. An engineer
concludes "per-thread queues are just faster." Is that the right
conclusion, and what would you need to check before trusting it?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Fan-in | The general shape of this problem: N producer threads, one consumer thread, all producer output eventually observed by that one consumer. |
| Claim point | The specific shared memory location producers must synchronize on to get a slot to write into — in a naive design, this is the *only* thing that's actually contended; the data slots themselves are not. |
| CAS retry loop | A producer's loop that reads the current claim position, attempts a compare-and-swap to advance it, and retries on failure — the direct cost of multiple producers racing for the same claim point. |
| Batched claim | Reserving several slots with one atomic read-modify-write (e.g. `fetch_add`) instead of retrying a CAS once per item — trades claim-point contention for coarser-grained backpressure waits. |
| Fan-in fairness | Whether the consumer observes roughly proportional output from every producer, or whether some producers are starved while others dominate — a property distinct from, and not implied by, high aggregate throughput. |
| Occupancy | How full the shared buffer is at a given moment; a consumer that silently falls behind (rising occupancy, or an unbounded queue growing without limit) is a real failure mode this lab requires reporting, not hiding behind a throughput number. |

## Required visualization: contention topology

A shared-structure MPSC has exactly one hot memory location every
producer must touch; a per-producer fan-in has none:

```
Shared MPSC:                     Per-producer fan-in:

  P1 --\                           P1 -> [ring 1] --\
  P2 ---+--> [claim point] -> Q         P2 -> [ring 2] ---+--> consumer
  P3 ---+       (contended)             P3 -> [ring 3] ---+   (round-robins)
  P4 --/                            P4 -> [ring 4] --/
```

### Textual fallback (contention topology)

| Design | Shared write-contended location(s) | What each producer pays |
|---|---|---|
| Single shared MPSC | One (the claim point) | A CAS attempt (successful or not) on every single item |
| Batched claims | One (the claim point), touched less often | One atomic RMW per *batch*, not per item |
| Per-producer fan-in | None | Zero — but the consumer now polls N structures instead of one |
| Mutex queue | One (the lock) | Blocking/spinning for exclusive access on every operation, producer AND consumer |
| Library queue (unbounded) | Whatever the JDK/std implementation uses internally | Unknown by design — it is a reference point, not a lab-authored mechanism |

## Required visualization: producer scaling curve

A conceptual view of aggregate throughput as producer count grows, for a
contended shared structure versus a design that removes the shared claim
point:

```
throughput
   |         ______------  per-producer fan-in (keeps scaling)
   |    ____/
   |   /
   |  /  ..........................  shared MPSC (flattens / degrades)
   | /..
   +--------------------------------> producer count
     1    2      4         8
```

### Textual fallback (producer scaling curve)

| Producer count | Shared MPSC (expected shape) | Per-producer fan-in (expected shape) |
|---|---|---|
| 1-2 | Comparable — little contention yet | Comparable |
| 4 | Visible falloff from ideal linear scaling | Closer to linear |
| 8 / physical-core count | Contention-dominated; may plateau or regress | Bounded mainly by the consumer's own poll cost, not by producer contention |

The lab's benchmark matrix records the real shape for this host — the
diagram above is the mechanism's predicted shape, not a substitute for
that measurement (see benchmark.md).

## Required visualization: queue occupancy timeline

A conceptual view of how full the shared buffer is over time, showing
the difference between a consumer keeping pace and one falling behind:

```
occupancy
   |                                    consumer falling behind
   |                                  ,-------------------------
   |                                 /
   |  ...consumer keeping pace...   /
   +--------------------------------------------------------> time
```

### Textual fallback (queue occupancy timeline)

| Pattern | What it means | Why it matters |
|---|---|---|
| Flat, low occupancy | Consumer drains as fast as producers fill | Healthy steady state |
| Rising, unbounded (or approaching capacity) | Consumer cannot keep pace | For a bounded queue: producers will start blocking/spinning on backpressure — a real, measurable cost this lab's "letting consumer fall behind without reporting occupancy" trap requires disclosing. For an unbounded queue: memory grows without limit, which no throughput number alone reveals. |

## Known traps

- **Comparing bounded and unbounded semantics.** The library-queue
  variant is unbounded by construction; a throughput comparison against
  the bounded variants without disclosing that difference would credit
  it for never paying backpressure cost — the semantics are different,
  not just the implementation.
- **Letting the consumer fall behind without reporting occupancy.** A
  benchmark that reports only producer-side throughput can hide a
  consumer that is silently losing ground; this lab's harnesses track and
  report final queue occupancy/backlog, not just items produced.
- **Ignoring producer fairness.** Aggregate throughput can be high while
  one producer starves — this lab's correctness oracle checks every
  producer's exact per-producer count and order, not just the total.
- **Using allocation-heavy payloads.** An encoded `long`/`i64` payload
  keeps the measured cost about queue mechanics, not allocator behavior;
  the library-queue variant's boxing (`Long` in Java) is disclosed
  explicitly as that variant's own inherent cost, not introduced into the
  lab-authored variants to make a point.

## Assumptions and scope

- Correctness for every variant is: exact total count
  (`producerCount × itemsPerProducer`), zero duplicates, and per-producer
  FIFO order preserved — global interleaving order *across* producers is
  never asserted, because MPSC makes no such guarantee.
- The benchmark matrix's correctness-suite producer counts are 2 and 4
  (kept small so `mvn test`/`cargo test` stay fast); the proposal's
  "8/physical-core producers" and "uniform vs skewed" and "burst traffic"
  dataset points are benchmark-matrix parameters, capability-detected
  against the actual measuring host's core count rather than hardcoded —
  see benchmark.md.
- `perf c2c`, hardware cache-transfer counters and async-profiler
  locks/cpu evidence are capability-detected; where unavailable on the
  native-Linux evidence host, this lab records that fact explicitly
  rather than substituting a different tool's output.
- The library-queue variant is a reference point, not a claim that either
  language's standard library queue is representative of a
  purpose-built design — see java.md/rust.md.
- The shipped harnesses currently wire the **uniform-producer** profile
  only (every producer emits the same `ITEMS_PER_PRODUCER` count); the
  proposal's "skewed producers" and "burst traffic" dataset points are
  not yet parameterized in the correctness/benchmark harnesses. This is
  disclosed as a scope gap in this iteration, not fabricated coverage —
  a future change extending the kernels to accept per-producer item
  counts and a burst/pacing schedule would close it without altering the
  correctness oracle (per-producer FIFO order and exact counts) this lab
  already establishes.

## Pre-lab diagnostic — answer

Not necessarily, and the improvement alone does not tell you *why*
throughput went up. At least two different mechanisms could explain it,
and they have very different implications: (1) the per-producer design
removed the shared claim point's contention (the mechanism this lab
teaches), or (2) the single shared queue's own implementation has a
different bottleneck entirely — for instance, an unbounded queue's
internal node allocation, or false sharing between the queue's own
internal head/tail fields — that per-thread queues merely sidestep as a
side effect, not because contention was the actual cause. Before trusting
"per-thread queues are faster" as a general rule, the team would need
contention evidence (CAS failure counts, or `perf c2c` cache-transfer
counts on the shared claim point) showing the shared design was actually
contended in the way the theory predicts, not just a before/after
throughput number — exactly this lab's "no claim that absence of observed
failure proves a memory-order algorithm correct"-style caution, applied
to contention claims instead.
