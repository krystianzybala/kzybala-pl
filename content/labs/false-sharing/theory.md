# False sharing — theory

## Performance question and hypothesis

**Question:** Why can independent counters destroy throughput when they
occupy the same cache line?

**Hypothesis:** Coherence traffic — not logical sharing — causes the
collapse; padding or ownership partitioning restores scalability.

**What would disprove it:** If the throughput gap between adjacent and
padded counters persisted with both threads pinned to the same core (no
cross-core coherence traffic possible), or if separating the counters onto
different cache lines did *not* restore throughput while everything else
stayed fixed, the coherence-traffic explanation would be wrong — the cost
would have to come from something else (the atomic operations themselves,
memory-ordering guarantees, or scheduler effects). The benchmark matrix in
`benchmark.md` is designed so each of those alternatives is separable from
the layout variable.

## Learning objective

Explain why two threads writing to **independent, unrelated variables** can
destroy each other's throughput, and be able to tell that failure mode apart
from a data race or lock contention — then know when padding is worth its
memory cost and when it isn't.

## Prerequisites

- Comfortable reading Java or Rust concurrent code (atomics, threads).
- Basic idea of what a CPU cache is (a fast copy of main memory kept close to
  a core). You do not need prior knowledge of coherence protocols — this lab
  builds that from scratch.

### Pre-lab diagnostic

Before reading further, answer this in one sentence: *two threads each
increment their own separate counter — no shared variable, no lock, no data
race. Can they still slow each other down, and if so, through what shared
resource?* If your answer names the cache line (or "the memory system"),
you can skim the next section. If your answer is "no, they're independent,"
that intuition is exactly what this lab corrects.

## Terminology

| Term | Meaning in this lab |
|---|---|
| Cache line | The fixed-size block (commonly 64 bytes) that caches transfer and track ownership of — never a single variable. |
| Coherence protocol | The hardware mechanism (MESI-like) that keeps all cores' cached copies of a line consistent. |
| Invalidation | A core's write forcing every other core's copy of that line to become stale. |
| Cache-to-cache transfer | A line moving directly between two cores' caches because one core needs what the other just wrote — the physical signature of false sharing (`perf c2c` measures it). |
| Ownership partitioning (sharding) | Restructuring so each thread writes only memory it exclusively owns, and readers reduce across shards — removing the shared line instead of padding around it. |

## Cache-line theory

CPUs do not move memory between cache and RAM one byte, or one variable, at a
time. They move it in fixed-size blocks called **cache lines** — commonly
64 bytes on current x86-64 and ARM64 parts, but the exact size is a hardware
detail, not an architectural guarantee (`CPUID`/`sysctl hw.cachelinesize` at
runtime if it matters to you). This lab treats 64 bytes as a *common*
example, never a universal constant.

When a core reads a memory address, the whole line containing it is pulled
into that core's cache. If two independent `long` counters happen to sit
inside the same 64-byte line — because they're adjacent fields in the same
object, or elements of the same small array — the hardware has no way to
know they are logically unrelated. As far as the coherence protocol is
concerned, there is exactly one cache line, and only one core may hold it
writable at a time.

**False sharing** is what happens when two threads on different cores
repeatedly write to *different* variables that live on that *same* line. Each
write by core A forces core B's copy of the line to be invalidated — even
though core B never touched core A's variable — and vice versa. The line
ping-pongs between cores, and every write pays the cost of a cross-core
transfer instead of hitting a warm local cache.

### A simplified coherence model

Real coherence protocols (MESI and its variants — MESIF, MOESI) have more
states and more nuance than this lab models. The interactive visualisation
below uses three states, which is enough to see the mechanism:

| State | Meaning |
|---|---|
| Shared | The line holds a clean, read-only copy. Other cores may also hold it. |
| Modified | This core has the only copy, and it has been written since the last fetch. |
| Invalid | This core's copy is stale and must be re-fetched before use. |

The rule that produces false sharing: **a write always forces every other
core's copy of that line to Invalid**, regardless of which bytes in the line
were actually written.

## False sharing vs. a data race

These are unrelated bugs that happen to both involve concurrent writes:

- A **data race** is a *correctness* bug: two threads access the same memory
  location without synchronization, and at least one is a write. The
  language memory model (JMM, Rust's) gives no guarantee about what value is
  observed — the program's behavior is undefined or unspecified. Fixing it
  requires synchronization (locks, atomics, happens-before edges).
- **False sharing** is a *performance* bug on code that is already correct.
  The two threads write to genuinely independent variables — there is no
  shared logical state and no synchronization needed for correctness. The
  cost is purely the extra coherence traffic caused by physical layout. Fixing
  it requires separating the variables onto different cache lines (padding,
  alignment, or just not colocating them), not adding synchronization.

A data race can exist without false sharing (racing on one line touched by
only one thread at a time, rare in practice) and false sharing can exist
without any race (every write is to a variable only that thread ever writes,
via `AtomicLong.set` or a plain non-shared field) — the two are orthogonal.

## False sharing vs. lock contention

- **Lock contention** happens when threads *want* the same critical section
  at the same time and must serialize — one waits (spins or blocks) while
  another holds the lock. The cost is explicit: queuing, context switches,
  or spin-wait cycles, and it is visible as threads *blocked on the same
  lock* in a profiler or thread dump.
- **False sharing** requires no lock at all. Both threads run concurrently
  and never wait for each other in the logical sense — there is no critical
  section, no `synchronized`, no shared invariant to protect. The threads
  make forward progress the whole time; the cost is hidden inside a slower
  memory subsystem, not visible as blocking. A profiler has to look at cache
  miss counters or coherence-traffic hardware counters to see it, not lock
  wait time.

The two can compound (a lock's own state — the lock word, a `volatile` flag —
can itself be a false-sharing victim if it shares a line with hot,
independently-written data), but eliminating one does not eliminate the
other.

## Common mistakes and benchmark traps

Four traps this lab is explicitly designed to avoid — check for each of
them before trusting any false-sharing measurement, including this lab's:

- **Using thread count without topology.** "Two threads" says nothing about
  *where* those threads run. Two SMT siblings of one physical core share an
  L1/L2 and never generate cross-core coherence traffic; two threads on
  different sockets pay far more per transfer than two cores of one die.
  A result that doesn't state (or control) thread placement is not
  reproducible — see the placement matrix in `benchmark.md`, including
  which placements this lab's host cannot express.
- **Padding local objects that are not adjacent.** Padding only does
  anything when the two hot variables would otherwise share a line. Padding
  a thread-local object, or two objects the allocator already placed far
  apart, changes nothing except memory footprint — and "the padded version
  measured the same" then gets misread as "false sharing doesn't matter."
  Verify adjacency (layout inspection, or the alignment assertions in this
  lab's tests) before crediting padding for anything.
- **Claiming `volatile` itself is the cause.** The counters in this lab are
  `volatile`/atomic in *every* variant — shared, padded, and sharded alike —
  and the collapse appears only in the shared-line layout. `volatile`
  ensures visibility ordering; it does not create the line ping-pong.
  Dropping `volatile` "to fix false sharing" trades a performance bug for a
  correctness bug and leaves the layout problem in place.
- **Using different memory-order guarantees across compared variants.** A
  Java `volatile` increment (sequentially consistent store) and a Rust
  `fetch_add(Relaxed)` are different contracts with different costs on weak
  memory architectures. Comparing them head-to-head measures ordering
  semantics as much as layout. This lab keeps ordering constant *within*
  each language's variant set and documents the cross-language difference in
  the equivalence contract (`benchmark.md`) instead of pretending it away.

Further mistakes worth knowing:

- **Padding by declaration order alone**, without `@Contended` or an explicit
  alignment attribute, and trusting it survives optimization or field
  reordering across every JVM/compiler version.
- **Assuming a universal 64-byte cache line.** Query it or state the
  assumption; some ARM cores use 128 bytes, some embedded targets 32.
- **Diagnosing false sharing as "just make it `volatile`"** or adding a lock
  — neither addresses layout, and a lock introduces contention that wasn't
  there before.
- **Padding everything defensively.** Padding has a real memory and
  cache-footprint cost (see "When not to use" below) — apply it to
  identified hot, independently-written fields, not preemptively everywhere.
- **Benchmarking on one thread.** False sharing is invisible in a
  single-threaded microbenchmark; the effect only appears with genuinely
  concurrent access from different cores, which is why the JMH example uses
  `@Group`/`Scope.Group` rather than plain per-thread benchmarks.

## When padding helps

- Independent counters/flags that are hot (frequently written) and written
  by *different threads running on different cores*.
- Fields with no other reason to be adjacent (not part of one atomically
  updated struct).

## When padding hurts or doesn't help

- **Read-mostly data.** Reads don't invalidate other cores' copies the way
  writes do — the shared-line cost is concentrated in write/write and
  write/read-after-write traffic. Padding read-mostly fields wastes memory
  for little gain.
- **Single-threaded or same-core access.** If nothing else ever touches the
  line concurrently from another core, there is no false sharing to fix.
- **Padding everything.** Extra padding inflates struct size, which reduces
  how much useful data fits in cache and can hurt prefetching and streaming
  access patterns across an array of structs — a cost in exchange for
  solving a problem you may not have.
- **Fields that are already independently allocated** (e.g., separate heap
  objects rather than adjacent fields) usually don't need explicit padding;
  the allocator rarely places small, unrelated objects on the same line
  deterministically enough to be a reliable problem — measure before adding
  padding here.

## Investigation task

Using the Java or Rust project in `code/`, and a profiler or hardware
counter tool available to you (e.g. `perf stat -e cache-misses` or
`perf c2c` on Linux, or JMH's own `-prof perfnorm`/`-prof perfasm` on
Linux):

1. Run the shared-counters benchmark and the padded-counters benchmark and
   record throughput for both.
2. Look for a cache-miss or coherence-event counter (e.g. `perf stat -e
   cache-misses`, or `perf c2c` on Linux for cache-to-cache transfer
   detection) and compare it between the two runs.
3. Reduce the benchmark to a single thread (comment out the second
   `@Benchmark`/thread) and confirm the shared-vs-padded gap disappears —
   this isolates the effect to concurrent cross-core access, not the layout
   alone.
4. Write down your CPU model, core topology (are the two threads pinned to
   different physical cores, or could they land on SMT siblings of the same
   core?), and your measured numbers — then compare against the disclosed
   numbers in `benchmark.md` and explain any difference you see.

## Limitations of this model

- Cache-line size is not universal — do not hardcode `64` as a language or
  architecture guarantee; query it or pad generously with a documented
  assumption.
- Real coherence protocols have more states (MESIF, MOESI), snoop filters,
  and directory-based coherence on many-socket systems, none of which this
  lab simulates.
- Padding has a real memory cost and, taken too far, hurts prefetching and
  cache footprint — see "When not to use" further down.
