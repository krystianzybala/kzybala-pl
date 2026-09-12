# Shared-memory IPC — theory

## Performance question and hypothesis

**Question:** how can two processes exchange fixed messages without
sockets or payload copies?

**Hypothesis:** a mapped SPSC protocol can achieve low one-way latency,
but crash recovery, ownership, memory ordering and versioning are harder
than the happy-path benchmark.

**What would disprove it:** if the shared-memory variants showed no
latency advantage over the socket baseline at all (the mechanism would
not actually be avoiding what it claims to avoid), or if the protocol
worked correctly with plain, non-atomic cursor fields (the cross-process
memory-ordering discipline would be unnecessary rather than load-bearing),
or if a restarted consumer could not correctly resume without either
re-reading already-consumed messages or losing unread ones (the "versioned
protocol, not just a fast happy path" half of the hypothesis would be
false).

## Learning objective

Show that avoiding sockets does not mean avoiding correctness work — it
moves the hard problems from "does the kernel deliver my bytes reliably"
(which TCP/pipes already solve) to problems the application must now solve
itself: a versioned wire protocol two independently-compiled programs both
honor, ordering discipline strong enough to be trusted across a process
boundary (not just a thread boundary), explicit lifecycle for the shared
segment (who creates it, who deletes it, what a crash leaves behind), and
an explicit recovery story for when one side restarts mid-stream.

## Prerequisites

- The [Memory-Mapped Files and Page Faults](/lab/memory-mapped-files/) lab
  — the shared segment here is exactly a memory-mapped file, just one two
  independent processes map with `MAP_SHARED` semantics instead of one
  process reading it.
- The [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab — the cursor protocol
  (reservation, publication, cached-cursor optimisation) is identical in
  structure; this lab's contribution is applying it across a process
  boundary and adding the versioning/lifecycle concerns that boundary
  introduces.

### Pre-lab diagnostic

A team's shared-memory IPC protocol passes every test on their laptop:
producer and consumer are two threads in one test process, both operating
on one shared `byte[]`/`Vec<u8>`. They ship it as "the cross-process
protocol." What does this test *not* actually prove, and what real
mechanism could it be silently missing?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Shared segment | The memory region — here, a memory-mapped file — that two separate processes both map, so writes by one become visible to the other through the OS-mediated page-cache-backed mapping, not through any language-level object reference. |
| Protocol version | An explicit field in the segment's header identifying which wire layout wrote it; a reader must check it before trusting any other field's meaning. |
| Cross-process publication | Making a write done by one process visible, in the correct order relative to other writes, to a different process reading the same physical memory — release/acquire discipline is what makes this correct rather than merely "usually works." |
| Recovery/resume | A process that restarts (after a crash, or a deliberate recycle) resuming its role using only what is recorded in the shared segment itself, not any state it remembered before restarting. |
| Same-process-threads trap | Testing a "cross-process" protocol using two threads in one process instead of two real OS processes — passes because JVM/process-heap visibility hides bugs that only manifest across a genuine mapping boundary. |

## Why this needs its own protocol, not just "SPSC ring buffer, but shared"

The [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab's cursor protocol
(single-writer `head`, single-writer `tail`, release-store publication,
acquire-load consumption, cached-cursor optimisation) is exactly the
mechanism this lab reuses — reservation, payload write, publication remain
three separate steps for the same reason. What changes is what "the other
side" is: a thread in the same process shares a JVM heap object or a
single virtual address space directly; two OS processes only share
physical memory pages through an OS-mediated mapping (`MAP_SHARED`, or
`FileChannel.map` in Java, `memmap2::MmapMut` in Rust) — there is no
shared language-level object at all, only two independent views over the
same bytes. Correctness now depends on:

1. **A versioned wire format** both programs agree on independently
   (there is no shared class/struct definition enforced by the compiler
   across a process boundary the way there is across threads in one
   binary) — a protocol-version field in the header lets a reader detect
   "I don't understand this segment's layout" rather than silently
   misinterpreting it.
2. **Ordering discipline that survives the process boundary.** Release and
   acquire orderings are a property of the CPU's memory model and the
   compiler's code generation, not of "being in the same process" — they
   work here for the same underlying hardware reason they work
   cross-thread, but there is no JVM/language runtime enforcing anything
   extra for you across processes; get the ordering wrong and the bug is
   just as silent as the SPSC lab's "publish before write" bug, now
   between two independently-compiled programs.
3. **Explicit segment lifecycle.** Something must create the segment,
   something must eventually remove it, and a process that dies mid-run
   must not leave the segment in a state a future reader misinterprets as
   valid — "leaking stale shared segments" is one of this lab's named
   traps precisely because there is no garbage collector for OS-level
   shared memory.
4. **An explicit recovery story.** A restarted process only knows what the
   segment itself records (its own cursor fields) — it cannot ask a
   previous process instance "where did you leave off," because that
   instance may no longer exist.

## Cross-process proof vs. the same-process-threads trap

It is tempting — and this lab names it explicitly as a trap — to test a
"cross-process" protocol with two threads sharing one in-memory object,
because it is fast and deterministic. That test proves the *cursor
arithmetic* is correct, but it cannot prove the *publication mechanism*
survives a real process boundary, because two threads sharing one Java
object or one Rust value never exercise the OS-mediated mapping at all —
whatever bug a broken release/acquire discipline would cause is masked by
JVM/process-internal visibility guarantees that have nothing to do with
this protocol's own correctness. This lab's actual proof spawns
`ProducerMain`/`producer` and `ConsumerMain`/`consumer` as genuinely
separate OS processes (see java.md/rust.md) — two independent programs,
each with its own address space, mapping the same backing file. The
same-process-threads variant used in this lab's own quick correctness
tests is explicitly documented as testing protocol *logic*, not the
cross-process *mechanism* — see java.md's and rust.md's test descriptions
for exactly where that line is drawn.

## Visualization 1: process/segment ownership diagram (textual fallback)

```
Process A (producer)                Process B (consumer)
+-----------------+                 +-----------------+
| own address space |               | own address space |
| ProducerMain/     |               | ConsumerMain/      |
| producer binary   |               | consumer binary     |
+--------+----------+               +----------+----------+
         |  mmap(MAP_SHARED)                    |  mmap(MAP_SHARED)
         v                                       v
   +----------------------------------------------------+
   |         Shared segment (backing file on disk)        |
   |  header: capacity, protocolVersion, writerSeq, readerSeq |
   |  slots: fixed-size messages, ring-indexed              |
   +----------------------------------------------------+
```

Both processes map the SAME backing file; the OS's page cache is what
makes writes from one process visible to the other, not any
language-level sharing.

## Visualization 2: sequence protocol animation (textual fallback)

```
Producer:  reserve slot -> write payload -> release-store writerSeq
Consumer:                                        acquire-load writerSeq
                                                  -> read payload -> release-store readerSeq
Producer (next iteration):                                            acquire-load readerSeq (only when ring looks full)
```

Same three-phase discipline as the SPSC ring buffer lab, now with each
arrow crossing a real process boundary rather than a thread boundary.

## Visualization 3: latency by payload size (textual fallback)

```
                    Illustrative dev-run shape (see benchmark.md for real numbers)
Socket baseline:            highest latency — kernel copy + context switch per message
Shared memory (copy):       lower — no socket syscall, but still one copy per message
Shared memory (slot view):  lowest for read-heavy access — no copy, direct field reads
Shared memory (batched):    amortizes the header-update cost across several messages
```

## Known traps

- **Using same-process threads and calling it IPC.** See "Cross-process
  proof vs. the same-process-threads trap" above.
- **Missing cross-process memory-order proof.** A protocol that "seems to
  work" in ad-hoc manual testing without an actual multi-process
  correctness test (like this lab's `ProcessLauncherTest`/
  `process_launcher.rs`) has not actually validated the mechanism that
  matters.
- **Leaking stale shared segments.** A crashed producer that never cleans
  up its backing file leaves a segment a future process might
  misinterpret as live — this lab's harness always uses a fresh temporary
  path per test/benchmark run specifically to avoid this.
- **Ignoring version mismatch and crash state.** `SharedRing.openExisting`/
  `SharedRing::open_existing` explicitly reject a segment whose
  `protocolVersion` doesn't match, rather than silently reading it as if
  it did.

## Assumptions and scope

- This lab's shared segment is a plain memory-mapped regular file, not a
  named POSIX shared-memory object (`shm_open`/`/dev/shm`) — the mapping
  mechanism (`MAP_SHARED` semantics) is the same either way, and a plain
  file avoids adding a platform-specific API binding neither language's
  standard library exposes directly; see java.md/rust.md for the
  practical difference this makes on Linux (`/dev/shm` is a tmpfs,
  avoiding disk I/O) versus this development machine.
- The "process restart/recovery scenario" variant is a correctness/
  resilience proof (does the resumed consumer see every message exactly
  once), not a load-bearing throughput benchmark; its "fault/recovery
  time" metric is illustrative wall-clock timing around one specific
  restart sequence, not a statistically characterized distribution.
- Correctness (round-trip delivery, protocol-version rejection, batch
  delivery, restart/resume behavior) is verified by a fixture shared
  identically by both languages' test suites, plus a dedicated real
  multi-process integration test — before any timing is trusted.
- This lab does not cover multi-producer or multi-consumer shared-memory
  protocols, or shared memory across machines (which sockets/RDMA, not
  `mmap`, would be the mechanism for).

## Pre-lab diagnostic — answer

The test proves the cursor arithmetic and the general publish/consume
logic are correct, but it does **not** prove the release/acquire
publication mechanism actually works across a real OS-mediated shared
mapping — because two threads sharing one in-process object never go
through that mapping at all. If the "release-store"/"acquire-load" calls
were accidentally replaced with plain, unordered reads/writes, a
same-process-threads test would very likely still pass: the JVM's or
Rust's memory model already guarantees enough visibility between threads
in one process for many buggy orderings to go unnoticed in practice,
especially at low thread counts and on a strongly-ordered CPU architecture
(the same "passes on x86 TSO, fails on a weaker model" trap the
[Memory Ordering](/lab/memory-ordering/) lab documents). The mechanism this
lab actually depends on — physical memory coherence between two
independent virtual-address-space mappings of the same pages — is
precisely what a real second OS process, not a second thread, is needed to
exercise.
