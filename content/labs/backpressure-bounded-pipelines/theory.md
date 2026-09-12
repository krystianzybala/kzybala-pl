# Backpressure and Bounded Pipelines — theory

## Performance question and hypothesis

**Question:** how should a low-latency system behave when producers are
faster than consumers?

**Hypothesis:** bounded queues make overload visible and controllable;
unbounded buffering preserves throughput briefly while destroying latency
and memory predictability.

**What would disprove it:** if an unbounded queue under sustained overload
showed *no* growth in memory or sojourn time (implying buffering is free),
or if a bounded policy's queue depth exceeded its configured capacity
(implying "bounded" wasn't actually enforced), the ownership-of-overload
model taught here would be wrong.

## Learning objective

Show that every overload policy is a different, explicit answer to one
question — "what happens to the Nth item when the queue is already full?"
— and that leaving the question unanswered (unbounded buffering) is itself
an answer: postpone the cost, pay it later as unbounded latency and memory
growth instead of a controlled, visible signal now.

## Prerequisites

- The [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab — this lab's bounded
  policies build directly on that lab's full/empty detection and
  backpressure discussion; a fixed-capacity ring is the mechanism, this
  lab is about the *policy* layered on top of it when the ring is full.
- The [MPSC Queues and Producer Contention](/lab/mpsc-contention/) lab —
  useful background on multi-producer fan-in, though this lab's
  simulation model is deliberately single-threaded and deterministic (see
  Assumptions and scope).

### Pre-lab diagnostic

A service reports "we never drop requests — our internal queue accepts
everything" as a reliability feature. Under a traffic spike, the same
service's p99 latency goes from 8ms to 45 seconds, and it eventually runs
out of memory and crashes, taking every request in its queue with it — the
requests it "never dropped." What is wrong with treating "never drops
requests" as unconditionally good?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Backpressure | An explicit signal to the producer (rejection, blocking, a shed decision) that the consumer cannot keep up, given *now*, before the cost becomes unbounded. |
| Sojourn time | How long one item actually waits in the queue between being accepted and being consumed — the metric that reveals queueing delay separately from processing time. |
| Bounded reject | A queue with a hard capacity that fails the producer's offer immediately when full — the producer learns about overload synchronously. |
| Bounded block | A queue with a hard capacity that makes the producer wait until space is available — overload becomes producer-side latency instead of an error. |
| Drop-oldest | A bounded queue that evicts its oldest pending item to admit a new one when full — trades old data for freshness under sustained overload. |
| Coalesce by key | A bounded structure keyed by identity, where a new update for an already-pending key overwrites the old one instead of growing the queue — appropriate when only the latest value per key matters (e.g. a price tick, a gauge). |
| Load shedding | Proactively rejecting some fraction of incoming work *before* the queue is completely full, to protect overall system health rather than waiting for hard capacity. |
| Coordinated omission | A load-generator measurement bug (see the [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab's exercises) that hides exactly the queueing delay this lab's sojourn-time metric is meant to expose — relevant to how this lab's own benchmark harness must be built. |

## Six answers to "what happens when the queue is full?"

1. **Unbounded (the anti-pattern).** There is no "full" — the queue grows
   to accommodate every accepted item. This preserves throughput and never
   rejects, which is exactly why it looks safe in a demo: nothing ever
   fails. Under sustained overload it fails differently and worse — sojourn
   time and memory grow without bound until the process is starved or
   killed, and every item still queued when that happens is lost anyway,
   just later and all at once instead of one at a time.
2. **Bounded reject.** The producer's offer fails immediately once the
   queue is at capacity. The producer finds out *now*, synchronously, and
   can retry, fail the request, or apply its own policy — the queue itself
   never grows past its bound.
3. **Bounded block.** The producer waits for room instead of failing.
   Nothing is lost, but the producer itself now experiences the queue's
   overload as latency — this converts consumer-side overload into
   producer-side backpressure, which is often exactly the right choice
   when the producer can tolerate waiting (e.g. a batch job) but wrong when
   the producer is itself latency-sensitive (e.g. a request thread).
4. **Drop-oldest.** When full, evict the oldest pending item to make room
   for the new one. This is a deliberate bet that *freshness* matters more
   than *completeness* — appropriate for live telemetry or market data
   where a stale item is worse than a missing one, wrong for anything that
   must eventually process every item (e.g. financial transactions).
5. **Coalesce by key.** Instead of a queue of items, keep at most one
   pending item per key; a new update for a key already pending overwrites
   it. This is not really "dropping" in the usual sense — no *distinct*
   update is lost if updates for the same key are meant to supersede each
   other (a sensor reading, a UI state), but it is wrong if every
   individual update carries independent meaning (e.g. an audit log).
6. **Load shedding.** Reject a controlled fraction of incoming work once
   occupancy crosses a threshold, *before* the queue is actually full —
   trading a small, controlled rejection rate now for protecting the
   system from ever reaching hard capacity at all. This is the only policy
   here that acts on a leading indicator (occupancy trend) rather than a
   trailing one (queue full).

## Sojourn time vs. end-to-end latency

An item's *sojourn time* is strictly the time it spends waiting in the
queue; *end-to-end latency* also includes whatever processing happens
before and after. Measuring only end-to-end latency under overload
conflates "the consumer is slow" with "the queue is backed up" — this
lab's benchmark records both, because a system that looks fine on
processing time alone can still be silently accumulating an ever-growing
queue.

## Recovery, not just steady-state behavior

Every dataset in this lab's benchmark matrix includes a burst or
sustained-overload phase *followed by* a long quiet period, specifically
to measure **recovery time** — how long it takes a policy to drain back to
a healthy queue depth once the overload ends. A policy that behaves
identically under overload can still differ sharply in how gracefully it
recovers: bounded-block's backlog, for instance, must fully drain before
new work proceeds at normal latency, while bounded-reject's queue is never
larger than capacity to begin with.

## Assumptions and scope

- This lab's correctness fixture is a **deterministic, single-threaded
  tick simulation** (a fixed per-tick production count, a fixed
  consumption rate, no real threads or wall-clock timing) — this is what
  lets the correctness tests assert exact counts instead of tolerances.
  The real-thread, real-timing question (how long each policy actually
  takes, and what its true sojourn-time distribution looks like) is
  answered separately by the JMH/Criterion benchmark harness (java.md,
  rust.md, benchmark.md), never by the deterministic fixture.
- "Load shedding" here uses a deterministic occupancy-threshold rule
  (shed every other arriving item once occupancy crosses a fixed
  percentage) rather than a random sampling rate, specifically so the
  correctness fixture remains exactly reproducible; a real system would
  typically use a randomized or adaptive shedding rate.
- Every dataset's production schedule ends with a long drain-only tail so
  every policy's queue is empty by the time the simulation ends — this
  is what makes the "produced = delivered + rejected + dropped +
  superseded" invariant hold exactly, with nothing left "still queued."
- This lab does not model network backpressure protocols (TCP flow
  control, HTTP/2 stream windows) — see the
  [UDP Ingest and Batching](/lab/udp-ingest-batching/) lab for
  transport-level concerns.

## Known traps

- **Reporting only successful requests.** A latency dashboard that
  silently excludes rejected/dropped/shed items reports a rosier picture
  than reality — this lab's metrics explicitly separate delivered from
  rejected/dropped/superseded rather than only tracking the former.
- **Hiding drops.** A system that drops but doesn't count or expose the
  drop makes "we never lose data" a claim nobody can actually verify —
  every non-unbounded policy in this lab's simulator counts every
  rejection, drop and supersession explicitly.
- **Using infinite producer retries.** A producer that retries forever on
  rejection just re-implements unbounded buffering, one level up, with the
  added danger of an unbounded retry queue nobody is watching.
- **Comparing different overload policies as equivalent.** Bounded-reject
  and drop-oldest solve overload very differently (fail-fast vs.
  fail-stale) — presenting them on one throughput chart without their
  distinct failure semantics invites exactly the wrong conclusion ("policy
  X is just faster") when the real difference is what each one sacrifices.

## Pre-lab diagnostic — answer

"Never drops requests" only sounds unconditionally good if buffering is
free — it is not. An unbounded queue under sustained overload doesn't
eliminate the cost of being overloaded; it defers and compounds it: every
accepted-but-unprocessed request sits in memory, sojourn time grows
without bound as the backlog grows, and if the process eventually runs out
of memory, every one of those "never dropped" requests is lost anyway, all
at once, along with the process itself. A bounded policy that rejects the
10,001st request when capacity is 10,000 loses far less (one request,
visibly, with a clear signal to the caller) and keeps the other 10,000
processing at bounded latency. The service's "reliability feature" was
actually deferring failure to the worst possible moment.
