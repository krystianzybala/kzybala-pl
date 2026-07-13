# Cache coherence and MESI — theory

## Learning objective

Explain why a write to a cache line requires exclusive ownership of that
line, distinguish cache **coherence** from memory **consistency** and from
**memory ordering**, and follow a cache line's state transitions across two
cores through read misses, write misses, read-for-ownership, invalidation,
cache-to-cache transfer, write-back, and eviction — while clearly separating
the conceptual MESI teaching model from what any specific real processor
actually implements.

## Prerequisites

- A basic idea of what a CPU cache is (a fast copy of main memory kept close
  to a core) and what a cache line is (see the
  [False sharing](/lab/false-sharing/) lab if you want that built up from
  scratch first — this lab does not require it, but the two reinforce each
  other).
- No prior knowledge of coherence protocols is assumed.

## Coherence vs. consistency vs. memory ordering

These three terms get conflated constantly. They answer different questions:

- **Cache coherence** answers: *for a single memory location, do all cores
  eventually agree on its value, and is there a well-defined order of writes
  to that one location that every core observes consistently?* Coherence is
  a per-location guarantee, enforced by hardware protocols like MESI, and it
  is what this lab explains.
- **Memory consistency** (the memory model) answers: *across multiple
  different memory locations, what orderings between operations is a program
  allowed to observe?* This is a much broader guarantee — or lack of one —
  that spans the whole memory system, not one line. Two coherent caches can
  still permit surprising reorderings across *different* addresses unless the
  consistency model forbids it.
- **Memory ordering** (fences, `volatile`, `Ordering::Acquire`/`Release`,
  `happens-before`) is the *language- and hardware-level mechanism* used to
  constrain what a consistency model otherwise allows — barriers that a
  programmer inserts (or a language runtime inserts on their behalf) to
  establish a needed order.

A concrete way to keep them apart: coherence guarantees that a single
`AtomicLong` will never appear to go backwards or fork into two values on
different cores. It says nothing about whether a write to a *different*
variable becomes visible before or after that one — that is a consistency
and memory-ordering question, and it is explicitly **out of scope** for this
lab (see "Non-goals" below and the companion memory-ordering lab).

## The MESI states

MESI names four states a cache line can be in, from any one core's point of
view:

| State | Meaning | May other cores hold a copy? | Is this copy dirty (differs from memory)? |
|---|---|---|---|
| **M**odified | This core has the only copy, and has written to it since it was fetched. | No | Yes |
| **E**xclusive | This core has the only copy, but has not written to it — it matches memory. | No | No |
| **S**hared | A clean, read-only copy. | Possibly, other cores may also hold Shared copies. | No |
| **I**nvalid | This core's copy is stale or absent and must be re-fetched before use. | — | — |

The state that makes MESI more than a toy: **Exclusive**. A cache line
fetched by a read, when no other core holds any copy, becomes Exclusive
rather than Shared — which means a *later write to that same line by the
same core* can silently upgrade Exclusive → Modified with no bus traffic at
all, because no other cache needs to be told anything. Without an Exclusive
state (as in a simpler MSI protocol), that same write would have to issue an
invalidation broadcast even though nothing else was sharing the line — pure
waste. This is the single biggest practical reason MESI exists.

## Transitions the interactive model demonstrates

- **Read miss, no sharers** — a core reads a line nobody else holds. It
  fetches from memory and takes it Exclusive.
- **Read miss, another core holds a clean copy** — the requesting core's
  read is satisfied by a **cache-to-cache transfer** from the holder rather
  than (or in addition to, depending on implementation) a fetch from memory.
  Both copies end up Shared; a lone Exclusive holder downgrades to Shared
  the moment a second reader appears.
- **Read miss, another core holds it Modified** — the dirty holder must
  supply its current value to the requester (cache-to-cache transfer) *and*
  flush it to memory (**write-back**), since memory's copy is now stale.
  Both end up Shared.
- **Write miss (read-for-ownership)** — a core wants to write a line it does
  not hold, or holds only as Shared. It must first invalidate every other
  copy (an invalidation is counted for each other holder) before it can
  become the sole Modified owner. If another core held the line Modified,
  that core's value is transferred and written back first, exactly as
  above, before being invalidated.
- **Write hit (Exclusive → Modified)** — no bus transaction needed at all;
  see "The MESI states" above.
- **Eviction** — a line leaves a cache (capacity or replacement policy, not
  modelled cycle-accurately here). If the evicted line was Modified, its
  value must be written back to memory first; if it was Exclusive or
  Shared, it is simply dropped, because memory (or another cache) already
  has a valid copy.

## Common mistakes

- **Treating MESI as *the* coherence protocol every CPU implements.** It is
  a teaching model. Real hardware ships MESIF (adds a Forward state so only
  one Shared copy answers a snoop), MOESI (adds an Owned state so a dirty
  line can be shared without an immediate write-back), or fully
  directory-based coherence (large multi-socket systems track sharers in a
  directory rather than broadcasting snoops on a shared bus) — see below.
- **Assuming coherence gives you memory ordering for free.** Coherence
  guarantees agreement on one location's value; it says nothing about the
  order in which writes to *different* locations become visible to other
  cores. That is a separate memory-consistency question.
- **Assuming Apple Silicon (or any specific chip) is "just MESI".** Vendors
  rarely publish their exact coherence protocol; what is public is
  necessarily incomplete or abstracted. Treat any specific-chip claim as
  unverified unless the vendor documents it.
- **Confusing invalidation with write-back.** Invalidation discards a stale
  copy; write-back flushes a dirty value to memory. A single transition can
  require both (see "Write miss" above) or just one (a clean eviction needs
  no write-back).

## Caveats: MESI is a teaching model, not a universal contract

- **MESIF** (used in some Intel processors) adds a **Forward** state:
  exactly one of several Shared-holding caches is designated to respond to
  a future read snoop, reducing redundant cache-to-cache responses.
- **MOESI** (used in some AMD processors) adds an **Owned** state: a
  Modified line can be shared with other cores *without* first writing back
  to memory — the Owned holder is responsible for supplying the data and
  eventually writing it back, and other cores may hold clean Shared copies
  of that same dirty value in the meantime.
- **Directory-based coherence** replaces broadcast snooping with a
  directory that tracks which caches hold which lines, used because
  broadcasting a snoop to every core stops scaling past a modest core/socket
  count. Large multi-socket servers commonly use directory-based schemes.
- **ARM and Apple Silicon**: ARM's architecture reference does not mandate
  one specific coherence protocol; implementers choose their own, and Apple
  has not published the exact coherence protocol used in its own silicon.
  This lab does not claim to model any of these implementations — it models
  the textbook four-state MESI protocol used to *teach* the mechanism, which
  is a common ancestor/simplification of what real hardware does, not a
  drop-in description of any one chip.
- **Replacement policy**: which line gets evicted under pressure (LRU,
  pseudo-LRU, random, etc.) is a separate, implementation-specific decision
  this lab does not model — the interactive model's eviction scenario is
  always a deliberate, explicit user action, not the result of a simulated
  replacement policy.

## Diagnostic methodology: perf c2c

This section describes how to *investigate* coherence traffic on supported
Linux systems — it deliberately does not present a synthetic "MESI
benchmark" as a portable performance number. Cross-core coherence cost
depends heavily on topology, core placement, and microarchitecture; a
single measured ratio here would invite exactly the kind of universal claim
this lab argues against.

`perf c2c` (Linux `perf`'s cache-to-cache module) samples hardware
performance-monitoring-unit events tagged with the physical cache line
address involved in a load, and groups them by which cores took cross-core
"HITM" (hit-modified) responses — cache-to-cache transfers of a dirty line,
exactly the transition the "competing writers" and "reader then writer"
scenarios model. It requires:

- A Linux kernel and CPU with the relevant PMU events available (`perf c2c
  record`/`perf c2c report`) — Linux-specific tooling, no macOS equivalent.
- Sufficient privileges to use hardware performance counters (commonly
  `CAP_PERFMON`/`CAP_SYS_ADMIN`, or a permissive
  `/proc/sys/kernel/perf_event_paranoid`).
- A workload that actually contends — a single-threaded program produces
  nothing interesting for `perf c2c` to report, by construction.

```sh
# Record cache-to-cache events for a running workload
perf c2c record -- ./your-contended-workload

# Summarize which cache lines saw the most HITM (cache-to-cache) traffic
perf c2c report --stdio
```

**Caveats:** exact event availability and column meaning vary by
microarchitecture and kernel version; virtualized environments frequently
restrict or disable the underlying PMU events entirely; and `perf c2c`'s
output identifies contended cache lines and the cores/threads touching
them, not MESI state names — the mapping from "HITM" back to "this was a
Modified→Shared transition" is an interpretation this lab teaches, not
something the tool states directly.

## Non-goals

- Cycle-accurate simulation of any real coherence protocol.
- Claiming MESI is universal, or that any specific chip (including Apple
  Silicon) implements exactly this protocol.
- Full memory-ordering / consistency-model semantics (see the companion
  memory-ordering lab for that).
- A full re-implementation of the False sharing lab's material — see that
  lab for the false-sharing failure mode this mechanism produces.

## Limitations of this model

- Four states only — no Forward (MESIF) or Owned (MOESI) state, and no
  directory-based coherence.
- Two cores only, one conceptual memory line — real systems have many cores
  and many lines, with associativity and replacement policy this model does
  not simulate.
- Eviction is a manual, explicit action here, not a simulated LRU/replacement
  decision under real capacity pressure.
