# Deterministic Low-Latency Pipeline Capstone — theory

## Performance question and hypothesis

**Question:** can the techniques from the curriculum combine into a small,
reproducible, bounded end-to-end runtime?

**Hypothesis:** a fixed-layout ingest-to-decision pipeline with
single-writer shards, bounded rings, zero-copy decode and explicit
overload policy can maintain predictable latency under controlled load.

**What would disprove it:** if the optimized, sharded, bounded pipeline
produced a *different* decision result than the naive baseline for the
same input (implying the optimizations changed correctness, not just
cost), or if hot-key skew caused overload identical to uniform load
despite one key dominating a single shard (implying sharding provides no
isolation at all), the compose-the-curriculum model taught here would be
wrong.

## Learning objective

This is a capstone: it does not introduce a new mechanism so much as
compose four mechanisms this curriculum already taught, in one runnable
pipeline, and hold it to the same evidence discipline as every other
lab. The point is showing that "assemble the pieces" is not automatic —
each seam (ingest → decode → shard → bound → decide) is a place a system
can silently regress if not measured explicitly, and a capstone that
skips measuring any one seam has not actually verified the combination
works.

## Prerequisites

This lab composes ideas from across the curriculum; it assumes fluency
with (not just familiarity with):

- [Fixed Binary Serialization](/lab/fixed-binary-serialization/) — the
  fixed-layout event encoding this pipeline ingests.
- [Backpressure and Bounded Pipelines](/lab/backpressure-bounded-pipelines/)
  — the per-shard bounded-admission policy is this lab's `BOUNDED_REJECT`
  policy, applied per shard.
- [CPU Affinity, NUMA and IRQ Placement](/lab/cpu-affinity-numa-irq/) —
  single-writer shard ownership depends on the same placement reasoning.
- [Coordinated Omission and Load Generation](/lab/coordinated-omission-load-generation/)
  — this lab's own evidence, once imported from the native-Linux host,
  must be measured with an open-loop-aware harness to be trustworthy.

### Pre-lab diagnostic

A team builds a pipeline using every "correct" individual technique from
this curriculum — bounded queues, sharding, zero-copy decode — but never
measures the pipeline end-to-end, only each piece in isolation. Under
real hot-key-skewed traffic, the assembled system misses its latency
target badly, even though every individual piece passed its own lab's
benchmark. What could go wrong when correct pieces are combined without
end-to-end measurement?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Fixed-layout event | A record of a known, constant byte size (32B/128B/1KiB in this lab) — what makes zero-copy decode possible: no length-prefix parsing needed to know where a field starts. |
| Single-writer shard | Exactly one worker thread owns each shard's state and processing — no cross-thread synchronization needed within a shard, only for routing an event *to* the correct shard. |
| Zero-copy decode | Reading fields directly from the event's own backing bytes (an offset view), rather than copying into a new heap object per event. |
| Hot-key skew | A key distribution where most events map to the same shard, defeating sharding's isolation for that one shard even though aggregate capacity across all shards looks fine. |
| Overload profile | The same pipeline, deliberately run with insufficient per-shard capacity relative to its offered load, to make its explicit drop policy observable. |
| Fault/restart profile | The same pipeline, with one shard forcibly reset mid-run, to verify the rest of the system continues correctly and to measure how it recovers. |

## The naive baseline: why it's the anti-pattern, not just "unoptimized"

The naive pipeline uses one shared, unbounded queue and (in the real
implementation, not this lab's deterministic correctness fixture) boxes
every event into a heap-allocated object before queueing it. This is not
merely "slower" — it is the composition of two anti-patterns this
curriculum already named individually: unbounded buffering (the
[Backpressure](/lab/backpressure-bounded-pipelines/) lab's anti-goal) and
per-event allocation on a hot path (a recurring theme from
[Allocation and Object Layout](/lab/allocation-object-layout/) and
[Escape Analysis](/lab/escape-analysis-scalar-replacement/)). Its role in
this lab's benchmark matrix is to make both costs visible side by side
with the optimized pipeline that avoids them, not to serve as a
strawman.

## Sharding is isolation, not just parallelism

Splitting work across N single-writer shards is often framed purely as a
throughput technique (more workers, more parallel capacity) — but this
lab's `mediumEventsHotKeyBurst` dataset demonstrates its other property:
**isolation**. Sharding by key means one overloaded key only saturates
*its own* shard; the other three shards keep processing normally. This is
directly testable: `maxShardDepth` under hot-key skew reflects one
shard's real occupancy, not an average smeared across all four — a
system that only reports aggregate queue depth would miss that one shard
is in real trouble while the others are idle.

## Overload and fault/restart: explicit failure modes, not surprises

The `OVERLOAD_PROFILE` and `FAULT_RESTART_PROFILE` variants exist to make
two failure modes an explicit, measured part of this lab rather than an
unplanned production surprise: what happens when capacity genuinely runs
out (drops, counted, never silent), and what happens when one shard needs
to restart mid-flight (the events queued there at that moment are
honestly counted as lost — `restartLoss` — never silently absorbed into
"delivered"). A capstone that only ever demonstrates the happy path has
not actually assembled a production-shaped system.

## Assumptions and scope

- Like every deterministic-simulation lab in this curriculum
  ([Backpressure](/lab/backpressure-bounded-pipelines/),
  [Observability Overhead](/lab/observability-overhead/),
  [Coordinated Omission](/lab/coordinated-omission-load-generation/)),
  this lab's correctness fixture runs on a virtual tick clock with no real
  threads or byte buffers — every variant's admitted/dropped/restart-loss
  counts and decision checksum follow from one deterministic recurrence.
  The real concurrent, real-buffer, real-timing version (genuine
  sharded worker threads, genuine fixed-size byte arrays, genuine
  zero-copy views) is exercised by the JMH/Criterion benchmark harness,
  never by the correctness suite.
- The decision checksum is a pure function of `(key, eventId,
  eventSizeBytes)` — it never depends on which pipeline variant or shard
  processed the event, which is what lets NAIVE and OPTIMIZED assert
  identical checksums when nothing is dropped.
- This lab does not implement a real network ingest path (UDP socket) —
  see the [UDP Ingest and Batching](/lab/udp-ingest-batching/) lab for
  that specifically; this lab's "ingest" is in-process event replay.

## Known traps

- **Declaring a language winner from one host.** This lab's entire
  purpose is composing mechanisms honestly, which makes the temptation to
  finally declare "Java wins" or "Rust wins" strongest here — the
  non-goal against a universal winner claim applies with full force to
  the capstone, not less.
- **Omitting correctness checks.** Skipping the shared correctness
  fixture "because it's just a demo pipeline" is exactly how the
  naive-vs-optimized checksum-parity guarantee this lab depends on would
  go unverified.
- **Using unbounded queues.** Reverting to an unbounded queue "just for
  the capstone, to keep it simple" reintroduces the exact anti-pattern
  the naive baseline exists to illustrate.
- **Hiding warm-up/startup.** A capstone benchmark that doesn't disclose
  JIT warm-up or allocator startup cost separately from steady-state
  numbers risks presenting a startup transient as the sustained latency
  claim.
- **Hand-tuning one implementation only.** Applying an optimization to
  only the Java or only the Rust side (rather than the semantically
  equivalent change in both) breaks the equivalence contract every other
  lab in this curriculum maintains.

## Pre-lab diagnostic — answer

Individually-correct pieces can still fail in combination because
correctness and interaction effects are different questions. A bounded
queue tested in isolation with uniform synthetic keys will never reveal
that real traffic's key distribution concentrates on one shard — that
is an *emergent* property of combining sharding with a real key
distribution, not a property either "sharding" or "bounded queues" have
on their own. Similarly, zero-copy decode tested alone says nothing about
whether it composes correctly with a specific shard's bounded admission
under contention. This is exactly why this lab exists as a distinct,
measured capstone rather than an assumed consequence of the individual
labs: assembling correct pieces requires its own end-to-end correctness
fixture and its own benchmark matrix, which is what `PipelineSimulator`
and the JMH/Criterion harness in this lab provide.
