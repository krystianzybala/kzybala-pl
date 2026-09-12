# CPU Affinity, NUMA and IRQ Placement — theory

## Performance question and hypothesis

**Question:** how much variance comes from *where* threads, memory and
interrupts run rather than from application code?

**Hypothesis:** pinning and first-touch placement can reduce migration and
remote-memory noise, but incorrect topology choices or shared IRQs can make
results worse.

**What would disprove it:** if pinning to a remote NUMA node performed
identically to pinning to the local node (implying remote memory access has
no real cost on this hardware), or if every placement produced identical
variance regardless of topology (implying placement never mattered), the
placement-matters model taught here would be wrong.

## Learning objective

Show that a benchmark's placement — which physical core a thread runs on,
which NUMA node its memory was allocated on, which IRQs share a core with
it — is a variable exactly like any other input, capable of dominating a
result if left uncontrolled, and that naming that variable explicitly
(rather than treating "the CPU" as one interchangeable resource) is what
separates a reproducible measurement from a lucky one.

## Prerequisites

- The [Cache Locality and Working Set](/lab/cache-locality-working-set/)
  lab — NUMA remote-access cost is memory locality at a coarser
  granularity: a whole node's worth of memory rather than a cache line.
- The [Memory Ordering: VarHandles and Rust Atomics](/lab/memory-ordering-atomics/)
  lab — background on why cross-core communication has a real hardware
  cost this lab measures directly via migrations and remote accesses.
- The [Thread-per-Core and Shared-Nothing Sharding](/lab/thread-per-core-sharding/)
  lab — this lab's `PINNED_ISOLATED_CORE` target is the placement half of
  that lab's ownership argument.

### Pre-lab diagnostic

Two engineers run the identical benchmark binary on the identical machine,
minutes apart, and get results that differ by 3x. Neither the code nor the
input changed. What is the most likely explanation, and what single piece
of information would let them find it?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| CPU affinity | A constraint (`sched_setaffinity` on Linux) restricting which CPUs the scheduler may run a given thread on. |
| Pinning | Affinity restricted to exactly one CPU — the thread never migrates, by construction, once verified. |
| SMT (simultaneous multithreading) | Two logical CPUs sharing one physical core's execution resources ("hyperthreads") — contending for the same cache and pipeline, not truly independent. |
| NUMA node | A group of CPUs with uniformly fast access to one region of memory; access to another node's memory is slower and less predictable ("remote"). |
| First touch | The common OS memory-allocation policy where a page is physically placed on the NUMA node of the thread that *first writes* to it, not the thread that allocated it. |
| IRQ affinity | Which CPU a hardware interrupt handler runs on — an interrupt-heavy device (e.g. a NIC) pinned to the same core as a latency-sensitive thread competes with it for that core's time. |
| Migration | The scheduler moving a thread from one CPU to another — invisible in application code, but real cost (cache/TLB cold-start on the new CPU) each time it happens. |

## Topology is real hardware, not a benchmark parameter

Every variant in this lab's benchmark matrix targets a placement decision,
never a code change: the exact same checksum computation runs unpinned,
pinned to an isolated core, pinned to an SMT sibling of a busy core,
pinned to a CPU on the same NUMA node as its data, and pinned to a CPU on
a different NUMA node from its data. If the computation's *result*ever
differed between these, that would be a correctness bug, not a
performance finding — this lab's fixture asserts exactly that these
checksums are placement-independent (java.md, rust.md), so that any
*timing* difference this lab's evidence run finds can be attributed
cleanly to placement, not to a hidden semantic difference.

## Migrations: the cost of "the scheduler moved you"

An unpinned thread is free to be moved between CPUs whenever the OS
scheduler decides to — for load balancing, power management, or simply
because another thread needed the core. Each migration is invisible at
the source-code level but not free: the moving thread's caches and TLB
entries on its old CPU are cold on the new one, and (if it moved to a
different NUMA node) its data may now be remote. A workload that looks
identical in every run can show real, reproducible variance purely from
how often and where the scheduler happened to move it — which is exactly
why "context switches" and "CPU migrations" are both required metrics
here, separately from wall-clock time: they are the *explanation* for
variance, not just more numbers.

## NUMA: memory has an address, and so does the CPU reading it

On a multi-socket (or advanced multi-die) machine, memory is physically
attached to specific CPUs' controllers; a CPU reading memory attached to
its own node pays local-access latency, while reading memory attached to
a different node pays a real, measurable remote-access penalty over the
inter-socket interconnect. "First touch" allocation means the *thread
that writes to a page first* determines which node the page physically
lives on — a common and easy-to-miss trap is allocating memory on one
thread and handing it to a worker thread pinned elsewhere, silently
making every access from that worker remote.

## IRQ interference: a hidden third tenant on your core

A pinned, isolated thread is not actually alone on its core if a busy
hardware interrupt (a network card servicing thousands of packets/second,
for instance) is also routed to that same core — the interrupt handler
preempts the pinned thread on every IRQ, adding jitter that looks like
inexplicable tail-latency noise unless IRQ affinity is checked
explicitly. This lab documents the mechanism but does not include a
runnable "IRQ interference" variant in its default matrix (see
Limitations): reproducing it safely requires host-level interrupt-routing
configuration this lab's default smoke/development experience must not
require.

## Assumptions and scope

- Every workload's correctness (its checksum) is asserted independent of
  placement — this lab measures *timing* differences from placement, never
  *correctness* differences, because there should never be any.
- Real topology facts (an isolated core id, an SMT sibling, a same/remote
  NUMA node) are probed from `/sys` at runtime on Linux and are **never
  hardcoded** — the known trap of "hardcoding CPU ids" this lab documents
  applies to values baked into source, not values discovered per-host.
- This repository's macOS development host has no `sched_setaffinity`
  and (being a single-package Apple Silicon SoC) no discoverable NUMA
  topology; every placement target other than `UNPINNED_BASELINE`
  therefore resolves to "unavailable" on this host and the lab's
  correctness tests still pass by falling back to unpinned execution —
  see benchmark.md for exactly what this means for the illustrative
  numbers below.
- The `IRQ_INTERFERENCE_PROFILE` variant named in this lab's design is
  documented here as theory but is not part of the runnable variant set,
  because reproducing it safely needs privileged host IRQ-routing
  configuration this lab's default experience must not require.

## Known traps

- **Hardcoding CPU ids.** A benchmark that pins to `cpu=4` unconditionally
  assumes every host has at least 5 CPUs and that CPU 4 means the same
  thing (an isolated core? an SMT sibling? a different socket entirely?)
  on every host — this lab's `Topology` probe discovers real ids per-host
  instead.
- **Requiring root for the default run.** `sched_setaffinity` on your own
  thread does not require elevated privileges on Linux; a lab that
  requires `sudo` for its smoke/development path has over-scoped what
  pinning actually needs.
- **Claiming results portable across topology.** A remote-NUMA penalty
  measured on a two-socket server says nothing quantitative about a
  single-socket laptop's SMT contention — this lab's conclusions (once
  native-Linux evidence lands) will be scoped to the specific host's
  topology, never generalized.
- **Pinning JVM GC/compiler threads indiscriminately.** Pinning every JVM
  thread — including GC and JIT compiler threads — to the same isolated
  core as your measured worker starves the runtime services that worker
  depends on; this lab pins only the one worker thread under test.

## Pre-lab diagnostic — answer

The most likely explanation is that the two runs had different thread/CPU
placement — one run's threads happened to land on cores sharing an SMT
pair, or spent time on a remote NUMA node, or migrated repeatedly, while
the other run got a cleaner placement, purely due to OS scheduling
decisions neither engineer controlled or observed. The single piece of
information that would let them find it is the hardware topology and
actual thread placement during each run — CPU migrations, NUMA node of
allocated memory versus NUMA node of the executing thread, and which
cores were shared with other load — exactly the metrics this lab's
benchmark matrix records instead of treating "ran on a CPU" as one
undifferentiated fact.
