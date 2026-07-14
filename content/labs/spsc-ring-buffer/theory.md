# SPSC ring buffer — theory

## Learning objective

Explain a bounded single-producer/single-consumer (SPSC) ring buffer as an
ownership-discipline problem more than an atomics problem: show why
separating reservation from publication, and payload read from
consumption acknowledgement, is what makes the structure correct, work
through wrap-around, full, and empty detection, show the cached-cursor
optimisation that avoids touching the other side's cursor on every
operation, contrast per-item publication with batching, and demonstrate two
concrete correctness bugs a naive implementation can introduce.

## Prerequisites

- The [CAS Contention and Backoff](/lab/cas-contention/) lab — this lab
  contrasts a ring buffer's single-owner cursors against a CAS retry loop's
  contended updates; understanding why single-writer designs have zero
  contention is assumed background here.
- The [Memory Ordering in Java and Rust](/lab/memory-ordering/) lab —
  publication below depends on the same release/acquire discipline that
  lab establishes.

## Why single-producer/single-consumer is a distinct case

A ring buffer that must support many producers and many consumers (MPMC)
needs contended, CAS-based cursor updates — the exact contention this
site's [CAS Contention and Backoff](/lab/cas-contention/) lab describes.
An SPSC ring buffer sidesteps that entirely: **the producer is the only
thread that ever writes the head cursor, and the consumer is the only
thread that ever writes the tail cursor.** Neither cursor is ever
contended, because exactly one thread owns each. This is the "single-writer
alternative" from that lab, applied structurally rather than as a
one-off comparison. The remaining work is not about avoiding contention —
there isn't any — it's about correctly *publishing* what one thread wrote
so the other thread observes it correctly, and correctly detecting full
and empty without needing a lock.

## Five separate phases, not two

It's tempting to think of a ring buffer operation as just "produce" and
"consume." This lab (and any correct implementation) separates each side
into two or three distinct steps, because collapsing them is exactly where
the bugs below come from:

**Producer side:**
1. **Reservation** — claim the next slot index. This only requires knowing
   there *is* a free slot; it does not touch the slot's memory yet.
2. **Payload write** — write the actual value into the reserved slot. This
   memory is not yet visible to the consumer.
3. **Publication** — advance the head cursor so the consumer can see the
   slot is ready. This is the release-store: everything written in step 2
   must be ordered *before* this step, or the consumer can observe a slot
   that looks ready but isn't (see "Bug: publish before write" below).

**Consumer side:**
4. **Payload read** — read the value out of a slot the producer has
   published.
5. **Consumption acknowledgement** — advance the tail cursor, telling the
   producer this slot is now free to reuse. Only after this step may the
   producer legally overwrite that slot.

## Wrap-around

The buffer has a fixed capacity `N`. Cursors (`head`, `tail`) are
monotonically increasing counters, not values bounded to `[0, N)` — the
actual slot a cursor refers to is `cursor % N`. "Wrap-around" is just this
modulo arithmetic taking a cursor from index `N-1` back to index `0` as it
keeps incrementing — it is normal, correct behaviour, not an edge case to
special-case away. The only invariant that must hold is that the producer
never reserves a slot the consumer hasn't yet freed, and the consumer never
reads a slot the producer hasn't yet published — both phrased purely in
terms of the *count* of outstanding items (`head - tail`), never in terms
of the wrapped index directly.

## Full and empty detection

- **Full**: the producer must not reserve a new slot when
  `head - tail == capacity` — doing so anyway is exactly the overwrite bug
  below. A correct implementation either rejects/blocks the reservation
  (backpressure) or the caller chooses a policy (drop, block, grow) — this
  lab's interactive model rejects and counts the rejection, the simplest
  honest choice for a fixed-capacity buffer.
- **Empty**: the consumer must not read when `head == tail` — there is
  nothing published yet. A correct implementation detects this and returns
  "nothing available" rather than reading undefined/stale slot content.

## The cached-cursor optimisation

Checking "is there room?" naively requires the producer to read the
consumer's tail cursor on every single reservation — a cross-core read of
memory the *other* thread is writing, which costs a coherence-traffic round
trip. Since the producer only actually needs the tail to be accurate when
its own optimistic view says "maybe full," a well-known optimisation is to
have the producer keep a **cached copy** of the consumer's tail (and
symmetrically, the consumer keeps a cached copy of the producer's head),
and only refresh that cache by re-reading the real cursor when the cached
value pessimistically suggests no room (or no data) is left. When the
cached value is already known to be sufficient, no cross-core read is
needed at all — that's the "cache hit" path in the interactive model below.
This is the same idea behind the cursor-caching pattern in the LMAX
Disruptor and comparable high-throughput SPSC/MPSC queues: touch the other
side's cursor as rarely as correctness allows, not on every operation.

## Batch publication

Publishing (and acknowledging) one slot at a time means one release-store
per item — real, if usually small, per-operation overhead. If the producer
has reserved several contiguous slots, it can write all their payloads
first and then publish **all of them with a single head update** — the
same idea as the consumer acknowledging several reads with a single tail
update. This amortises the publication/acknowledgement cost across a batch
without changing the reservation/write/publish ordering *within* the
batch, and without batching the payload read step itself, since each
item's payload still has to be read individually.

## Backpressure

A bounded buffer that is full has exactly two honest choices when a
reservation fails: **reject/signal the caller** (this lab's model) or
**block until the consumer frees a slot**. What a correct implementation
must never do is silently overwrite an unconsumed slot to "make room" —
that is not backpressure, it is data loss, and it is the specific bug this
lab demonstrates below.

## Bug: publish before write

If a producer advances the head cursor *before* finishing the payload
write — for example because the release-ordering discipline from the
[Memory Ordering](/lab/memory-ordering/) lab was skipped, or the two steps
were simply written in the wrong order — the consumer can observe the slot
as "published" and read it before the real payload exists. It doesn't
crash: it silently returns whatever was previously in that memory (stale
data from an earlier cycle, or uninitialized memory on a fresh buffer).
This is why publication is listed as its own explicit step above, ordered
strictly after the write: get that ordering wrong and the bug is silent,
not a fault.

## Bug: overwrite before consumption

If a producer's reservation step does not correctly check `head - tail`
against capacity — for example, an off-by-one in the check, or (as this
lab's buggy scenario models directly) a version that never performs the
check at all — the producer will eventually reserve and overwrite a slot
the consumer has not yet read. The old value is destroyed with no error,
no exception, and no signal to either side that data was lost. This is
strictly worse than a crash, because the program keeps running as if
nothing went wrong.

## Limitations of this model

- The interactive model below fixes a small capacity (2 or 4 slots
  depending on scenario) and a hand-authored turn order so each mechanism
  is reachable in a handful of steps — a real SPSC buffer's capacity is
  chosen for the actual workload's burst size, typically much larger.
- "Cache hit" and "cache refresh" in the model are exact, deterministic
  counts for one specific scripted sequence of operations, not a
  measurement of real cross-core coherence-traffic savings — see
  "Benchmark methodology" below for real, disclosed timing data.
- This lab does not cover multi-producer or multi-consumer variants —
  see the [CAS Contention and Backoff](/lab/cas-contention/) lab for the
  contention those variants introduce.
