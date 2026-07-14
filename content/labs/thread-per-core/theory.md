# Thread-per-core architecture — theory

## Learning objective

Explain thread-per-core as an ownership and execution model — not merely
"one thread per logical CPU" — contrast it against a shared worker pool,
show how partitioned ownership eliminates lock contention but introduces
cross-core handoff cost, demonstrate that partitioning alone does not fix
load imbalance, explain the affinity/scheduler and NUMA caveats that
determine whether the model's assumptions actually hold on real hardware,
and show bounded-queue backpressure as the honest response to overload.

## Prerequisites

- The [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab — cross-core handoff
  below is exactly one SPSC ring buffer per core-to-core link; this lab
  assumes the reservation/publication/read/acknowledgement discipline from
  there.
- The [CAS Contention and Backoff](/lab/cas-contention/) lab — the shared
  worker pool's lock contention below is the same "many threads wanting one
  location" cost that lab describes, just via a lock rather than a bare CAS
  loop.

## Thread-per-core is an ownership model, not a thread count

"One thread per logical CPU" describes a *number*. Thread-per-core
describes a *discipline*: each thread exclusively **owns** a partition of
the application's state for its entire lifetime, and no other thread ever
touches that partition directly. The number of threads matching the core
count is a consequence of wanting each owning thread to run essentially
uncontended on its own core — it is not the definition. A program that
spawns exactly one thread per core but still lets any thread touch any
shared data through a lock is a worker pool sized to the core count, not a
thread-per-core architecture.

## Shared worker pool vs. owned-state execution

**Shared worker pool**: N worker threads, all able to process any request,
coordinating through shared state protected by a lock (or an atomic retry
loop — see the [CAS Contention](/lab/cas-contention/) lab). This is
simple, flexible (any worker can pick up any request, so load naturally
balances across workers), and exactly as contention-prone as any other
shared mutable state: every request that touches the shared state pays the
lock's cost, and only one request can hold that lock at a time —
**regardless of how many workers exist.** The theoretical N-way
parallelism does not materialize on the operations that actually touch
shared state.

**Thread-per-core ownership**: each core's thread owns a disjoint
partition of the state (e.g. by hashing a key to a core index) and only
that thread ever reads or writes its partition. There is no lock, because
there is no sharing — this is the "single-writer alternative" from the
CAS Contention lab, applied as the primary architecture rather than a
one-off contrast. A request whose partition happens to live on the core
that received it is processed with zero synchronization. This buys real
parallelism on the operations that dominate — at the cost of needing an
explicit mechanism for the case where a request arrives on the wrong core.

## Cross-core handoff

If a request for partition P arrives on a core that isn't P's owner, it
must be handed off — never processed directly by the wrong core, since
that would silently reintroduce the shared-mutable-state problem
ownership was supposed to eliminate. The standard mechanism is exactly one
bounded SPSC channel per ordered pair of cores (the arrival core is the
sole producer into that channel, the owning core is the sole consumer),
using the same reservation → publication → read → acknowledgement
discipline the [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab establishes.
Handoff is not free: a handed-off request incurs at least one extra
scheduling round through the channel before its owning core even sees it,
which this lab's interactive model represents as one extra step of
latency compared to a request that arrived directly on its own partition's
core.

## Hot partitions: ownership does not equal balance

Partitioning state by owner does not, by itself, guarantee that traffic is
evenly distributed across those partitions. If one key (or one narrow
range of keys) is disproportionately popular, its owning core's inbox
queue grows while every other core sits idle — the exact same total
capacity is available, but concentrated demand cannot spill over onto
underused cores the way it could in a shared worker pool. A correct
thread-per-core implementation must still contend with **hot partitions**
as a distinct, ongoing operational problem: repartitioning, splitting a
hot key further, or accepting bounded queueing/backpressure on the hot
core are the standard responses — there is no way to make the ownership
model itself immune to a skewed key distribution.

## Backpressure

Every core's inbox is bounded, exactly like the [SPSC Ring
Buffer](/lab/spsc-ring-buffer/) lab's buffer capacity. When a core's inbox
is full, the correct behaviour is to reject (or have the sender block)
rather than let the queue grow without bound — an unbounded queue under
sustained overload only converts memory pressure into unbounded latency
before eventually failing anyway. Rejecting immediately, and letting the
caller decide (retry, shed the request, apply upstream backpressure) is
the honest response to genuine overload, not a failure of the design.

## Affinity and scheduler caveats

Thread-per-core's zero-contention argument assumes each owning thread
keeps running on (approximately) the same physical core, so its
partition's data stays resident in that core's cache. This is not
automatic:

- **CPU affinity support differs by OS.** Linux exposes `sched_setaffinity`/
  `taskset` and (via `pthread_setaffinity_np`) fairly direct control.
  macOS deliberately does **not** expose hard affinity to user processes —
  `thread_policy_set` with `THREAD_AFFINITY_POLICY` is only a hint the
  scheduler may ignore. Windows exposes `SetThreadAffinityMask` /
  processor groups, with its own scheduling quirks.
- **Containers and cgroups add another layer.** A container's CPU quota
  and cgroup CPU-set restrictions can constrain or interact with affinity
  in ways that differ from bare-metal behaviour, and orchestrators may
  migrate or rebalance containers across physical hosts entirely outside
  the application's control.
- **Even with affinity requested, the OS scheduler can still migrate a
  thread** — for priority, power management, or thermal reasons — unless
  the platform's affinity mechanism is a hard guarantee (which, per above,
  it often isn't). A migration does not break correctness (the thread
  still owns the same logical partition, and still processes it
  correctly) — it degrades performance, by losing whatever cache locality
  had built up on the old physical core. This lab's "Scheduler migration"
  scenario models exactly this: ownership is unaffected, only locality is
  lost.

## NUMA caveats

On multi-socket (NUMA) hardware, memory attached to one socket is more
expensive for a core on a different socket to access. Thread-per-core's
benefit compounds with NUMA-aware placement — pin a partition's owning
thread to a core *and* allocate that partition's memory on the same NUMA
node — but this lab's model does not simulate NUMA distance, memory
allocation policy, or interconnect topology. A production
thread-per-core system on NUMA hardware needs an explicit memory
allocation strategy (e.g. `numactl`, `libnuma`, or an allocator that is
NUMA-aware) in addition to CPU affinity; affinity alone only controls
where the thread *runs*, not where its memory was allocated.

<div class="disclosure conceptual">
  <p class="disclosure-kind">Conceptual model</p>
  <p>The interactive model below fixes 4 cores and a small, hand-authored sequence of request arrivals so each mechanism is reachable in a handful of steps. "Turns" are a step count, not measured time; queue depth and lock-acquisition counts are exact for one scripted sequence, not a measurement of real coherence or scheduling cost — see "Benchmark methodology" below for real, disclosed timing data. The model does not simulate CPU affinity, NUMA distance, or the OS scheduler itself — the "Scheduler migration" scenario injects a single migration event to make its consequence concrete, not to reproduce real scheduler behaviour.</p>
</div>
