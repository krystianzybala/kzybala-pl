# Thread-per-core and shared-nothing sharding — theory

## Performance question and hypothesis

**Question:** can ownership partitioning remove synchronization from the
hot path?

**Hypothesis:** stable key-to-core sharding and single-writer state can
reduce coherence and locks, but routing, skew and rebalancing become
first-class costs.

**What would disprove it:** if the single-writer thread-per-core variant
showed no reduction in per-key write contention relative to the shared
map (ownership partitioning wouldn't actually be removing synchronization
from the write path); if the skewed dataset showed no throughput
difference from uniform for the sharded designs (key skew wouldn't
actually create a hot-shard bottleneck); or if the rebalance simulation's
measured pause were negligible regardless of workload size (rebalancing
wouldn't actually be a first-class cost worth measuring) — any of these
would mean the model taught here is wrong for this workload.

## Learning objective

Explain sharding as a trade: moving synchronization from "every write"
to "every routing decision" — show a concrete case where that trade
does *not* pay off (routing overhead can exceed the lock contention it
replaces, especially for cheap per-item work), demonstrate what a single
hot key does to an otherwise well-partitioned design, and treat a
rebalance operation's own cost as data, not as a detail to hand-wave.

## Prerequisites

- The [Locks, Spin Waiting and Parking](/lab/locks-spin-parking/) lab —
  this lab's mutex-shards variant is a direct application of that lab's
  mutex-vs-lock-free trade-offs, applied per shard instead of globally.
- The [MPSC Queues and Producer Contention](/lab/mpsc-contention/) lab —
  this lab's single-writer variant routes requests to each shard's owner
  through the same many-producers-one-consumer shape that lab covers;
  the routing queue itself is not re-derived here.
- The [Thread-per-Core](/lab/thread-per-core/) lab — the general
  ownership-partitioning idea this lab specializes to a sharded
  key-value counter workload.

## Pre-lab diagnostic

A team partitions a shared cache into 8 shards, one owning thread per
shard, keyed by `hash(key) % 8`, expecting an 8x reduction in lock
contention. In production, one specific key (a popular product id)
receives 40% of all traffic. Does the 8-shard design still deliver
close to an 8x improvement for this workload? Why or why not?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Shard | A disjoint partition of the key space, routed to by a stable function of the key (e.g. `key % shardCount`) — every key belongs to exactly one shard, always. |
| Single-writer | A shard design where exactly one thread ever mutates that shard's state — no lock is needed on the state itself, only on however requests are routed to that thread. |
| Routing cost | The overhead of getting a request to the thread that's allowed to handle it — for a single-writer design, this is a queue send/receive; it does not disappear just because the state mutation itself became lock-free. |
| Key skew | An uneven distribution of requests across keys — a small number of "hot" keys receiving a disproportionate share of traffic. |
| Hot shard | The shard that owns a hot key — even with perfect partitioning elsewhere, a hot shard's single owner thread becomes the bottleneck for that key's traffic, since nothing shards *within* one key. |
| Rebalancing | Changing which thread/shard owns which keys, typically to correct load imbalance discovered after the fact — a real operation with a real cost (draining in-flight state, updating routing), not a free reconfiguration. |

## Required visualization: sharding topology

```
Shared map:                  Fixed shards + mutex:         Single-writer thread-per-core:

  R1 --\                       R1 --\                        R1 --\
  R2 ---+--> [one map]         R2 ---+--> [shard0 mutex]      R2 ---+--> [shard0 inbox] -> owner0
  R3 ---+     (every key       R3 ---+--> [shard1 mutex]      R3 ---+--> [shard1 inbox] -> owner1
  R4 --/       contends)       R4 --/     (per-shard lock)    R4 --/    (routing, not locking)
```

### Textual fallback (sharding topology)

| Design | What's synchronized | What isn't |
|---|---|---|
| Shared map | Every key, on every write (one shared structure) | Nothing |
| Fixed shards + mutex | Each shard's lock, contended only by requests to that shard's keys | Cross-shard writes never contend with each other |
| Single-writer thread-per-core | The routing queue into each shard (a send/receive, not a lock on the data) | The shard's own state — only its owner thread ever touches it |

## Required visualization: skew impact on a sharded design

```
throughput per shard
   |
   |  ####                (shard 0 — owns the hot key — saturated)
   |  ####
   |  #### ..    ..    .. (shards 1-3 — mostly idle, waiting for
   |  #### ..    ..    ..  work that was never routed to them)
   +------------------------------------------> shard id
      0     1     2     3
```

### Textual fallback (skew impact)

| Scenario | Per-shard load | Aggregate throughput |
|---|---|---|
| Uniform keys, N shards | Roughly `total / N` per shard | Scales close to linearly with N |
| One hot key, N shards | The hot key's shard saturates; others are underutilized | Bounded by the hot shard alone — adding more shards elsewhere does not help the hot key's traffic at all |

## Required visualization: rebalance cost timeline

```
throughput
   |----phase 1 (mapping A)----| PAUSE |----phase 2 (mapping B)----|
   |############################|       |############################|
   +---------------------------------------------------------------> time
                                  ^ drain + remap + resume
                                    (measured, not assumed free)
```

### Textual fallback (rebalance cost timeline)

| Phase | What's happening | Cost this lab measures |
|---|---|---|
| Before rebalance | Steady-state processing under the original mapping | (baseline throughput) |
| Rebalance pause | Draining in-flight work, updating the routing table | `rebalanceCostNanos` / `rebalance_cost_nanos` |
| After rebalance | Steady-state processing under the new mapping | (baseline throughput, possibly different if the new mapping is better/worse balanced) |

## Known traps

- **Hiding routing cost.** This lab's own smoke-run numbers show the
  single-writer variant costing *more* than the mutex-shards variant for
  this cheap-per-item workload (see benchmark.md) — the routing queue's
  own overhead (channel send/receive, node allocation) is a real cost
  that does not disappear just because per-key locking did. Reporting
  only "no lock contention" without this number would hide exactly the
  cost this trap warns about.
- **Using random keys that eliminate realistic skew.** The skewed
  dataset exists specifically because a uniform-random key generator
  would never expose the hot-shard problem the sharding topology
  visualization above depends on.
- **Pinning without topology disclosure.** This lab's kernels do not pin
  worker threads to specific CPUs (see Assumptions and scope) — any
  future extension that adds pinning must disclose the exact topology
  and mapping used, not just claim "thread-per-core" while the OS
  scheduler is free to move threads anywhere.
- **Calling shared-nothing globally lock-free.** The single-writer
  design removes locking on shard *state*, not on the routing mechanism
  — the MPSC channel each shard reads from has its own internal
  synchronization (see the prerequisite MPSC lab). "Shared-nothing" here
  describes the application state, not the entire system.

## Assumptions and scope

- Correctness for every variant is: every key's final counter equals the
  exact expected request count for that key under the selected dataset
  (uniform or skewed) — this holds regardless of mechanism.
- The shipped harnesses wire the **per-key counters** dataset only (a
  simplified stand-in for "account/order state updates"); the proposal's
  "partitioned aggregation" dataset point is not yet parameterized in
  this iteration — disclosed here rather than fabricated, the same
  pattern as this lab's prerequisites' own scope gaps.
- The rebalance simulation is a **full stop-the-world model**: phase 1 is
  completely drained and joined before phase 2 begins, which is what
  makes it safe without extra synchronization on the shared counters
  array (the `join()` calls are the happens-before edge) — it does not
  model a *live* migration that keeps serving requests during handoff,
  and its measured "rebalance cost" reflects that simplification's own
  bookkeeping, not a realistic production migration cost.
- No CPU pinning/affinity is implemented in these kernels — "thread-per-
  core" here describes the ownership-partitioning *design*, not literal
  OS-level core pinning; a native-Linux evidence run could add real
  affinity via the shared runner's worker-placement conventions, but
  this iteration's harnesses do not read any `-Dplab.cpu*`-style pinning
  property.
- `perf c2c`, async-profiler and JFR evidence are capability-detected;
  where unavailable on the native-Linux evidence host, this lab records
  that fact explicitly rather than substituting a different tool's
  output.

## Pre-lab diagnostic — answer

No — the 8-shard design does **not** deliver close to an 8x improvement
for this workload, and the reason is specific: sharding partitions the
*key space*, not any individual key's traffic. All 40% of requests for
the popular product id are routed, by the same stable hashing function
every request uses, to the exact same shard every time — that shard's
single owner thread must process all of that 40% sequentially, exactly
as if there were no sharding at all for that key's traffic. The other 7
shards can run in parallel just fine, but the *aggregate* throughput
ceiling is set by the busiest shard, not by the average across shards —
this is precisely this lab's "skew impact on a sharded design"
visualization. Fixing this specific problem requires either splitting
the hot key's own load further (which requires abandoning strict
single-writer semantics for that key, or replicating/aggregating it
differently) or detecting and rebalancing around it — which is exactly
why this lab treats rebalancing as a first-class, separately-measured
cost rather than an assumed-free fix.
