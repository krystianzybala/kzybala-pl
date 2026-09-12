# Virtual Threads vs Platform Threads vs Event Loops — theory

## Performance question and hypothesis

**Question:** which concurrency model fits blocking I/O, CPU-bound work and
strict tail-latency paths?

**Hypothesis:** virtual threads improve blocking-task scalability but do not
create CPU capacity or guarantee low-latency scheduling; event loops and
pinned platform threads remain useful for owned hot paths.

**What would disprove it:** if virtual threads sustained the same throughput
as platform threads *and* removed a CPU-bound long-stage bottleneck (implying
they conjure extra cores), or if a single event loop absorbed a long CPU
stage without stalling every other queued task (implying it isn't actually
single-threaded), the ownership-of-capacity model taught here would be wrong.

## Learning objective

Show that "cheap thread" (virtual threads) and "more CPU capacity" are
different properties: virtual threads make *blocking* nearly free by
unmounting from their carrier while parked, so thousands of blocked tasks
cost little; they do nothing for a task that never blocks, because CPU-bound
work still needs an actual core the whole time. Explain why a single-thread
event loop never blocks on I/O but still serializes all CPU work, and why
isolating CPU-bound work onto its own bounded pool is a real hybrid pattern.

## Prerequisites

- The [Thread-per-Core and Shared-Nothing Sharding](/lab/thread-per-core-sharding/)
  lab — this lab's "CPU-bound pool" variant assumes the same worker-owns-a-core
  reasoning; the difference here is which *scheduler* decides how work reaches
  those workers.

### Pre-lab diagnostic

A team switches its request handlers from a platform-thread-per-request
pool to `Executors.newVirtualThreadPerTaskExecutor()` and sees throughput on
their I/O-heavy endpoint jump 20x. They then apply the same change to a
CPU-bound image-resizing endpoint and see no improvement at all. Why did one
endpoint benefit enormously and the other not at all?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Platform thread | A JVM thread backed 1:1 by an OS thread; expensive to create in the thousands (stack memory, OS scheduling overhead). |
| Virtual thread | A JVM-scheduled, cheaply-created task that runs on a small pool of *carrier* platform threads, and unmounts from its carrier while blocked so the carrier can run something else. |
| Carrier thread | The platform thread a virtual thread is currently mounted on; virtual threads share a small, fixed carrier pool. |
| Pinning | A virtual thread that cannot unmount from its carrier while blocked (historically: inside `synchronized`, or during certain native calls), so it occupies a carrier the whole time it is blocked — defeating the point of using a virtual thread there. |
| Event loop | A single thread that never blocks on I/O — it registers a callback/continuation and moves on — but any CPU-bound work it performs directly still runs serially on that one thread. |
| Coordinated omission | Not the direct topic here, but relevant to this lab's own benchmark methodology (see [SPSC Ring Buffer](/lab/spsc-ring-buffer/)'s exercises): a load generator that waits for each response before issuing the next hides queuing/stall latency. |

## Platform threads: one OS thread per task

The simplest model: every task gets its own platform thread. Correct and
simple, but each platform thread reserves real OS resources (a stack,
kernel scheduling metadata), so thread-per-request under high blocking-I/O
concurrency (thousands of simultaneous, mostly-idle connections) eventually
exhausts memory or hits OS thread-count limits long before it exhausts CPU.

## Virtual threads: cheap tasks, not extra cores

A virtual thread is scheduled by the JVM onto a small pool of carrier
platform threads (typically sized to the core count). While a virtual
thread is *blocked* on a blocking call the JVM recognizes (I/O, `Thread.sleep`,
`LockSupport.park`, and — as of JDK 24, JEP 491 — most `synchronized`
blocks too), it **unmounts**: its carrier is freed to run another virtual
thread. This is why a program can run millions of virtual threads that are
mostly waiting, at a cost proportional to how many are *actually running*
at once, not how many exist. Critically, this says nothing about CPU
capacity: a virtual thread doing CPU-bound work never unmounts (there is
nothing to unmount from — it is actively using its carrier), so N virtual
threads all computing at once still contend for the same small number of
carriers, exactly like N platform threads would contend for the same
number of cores.

## Pinning: when "cheap" stops being true

If a virtual thread cannot unmount while blocked — the historical example
being a blocking call made *inside* a `synchronized` block, before JEP 491 —
it occupies its carrier for the whole blocking duration, exactly like a
platform thread would. A program relying on "virtual threads never cost a
carrier while blocked" silently degrades back to platform-thread-style
carrier exhaustion the moment pinning happens, without any visible error —
which is exactly why this lab's `lockNativePinningCase` dataset exists: to
make this failure mode concrete rather than theoretical (see Limitations
for what this specific JDK toolchain can and cannot demonstrate).

## Event loops: never block, but never parallelize either

A single-thread event loop takes the opposite approach: instead of one
thread per task, one thread runs a queue of callbacks, and a blocking
operation is represented as "register a callback for when this completes,"
never as "pause this thread." This means the loop thread is *never*
occupied waiting on I/O — but it also means the loop thread is the only
thing that can ever execute application code. A CPU-bound stage placed
directly on the loop stalls every other queued callback for its entire
duration; an event loop trades "many cheap waiters" for "exactly one
worker," and that worker must never be given long CPU work directly.

## CPU-bound pools and the "isolate CPU work" pattern

A fixed pool sized to the core count is the right tool specifically for
CPU-bound work: more worker threads than cores cannot increase throughput
(there is no more CPU to give them), only add scheduling overhead. The
practical synthesis this lab measures directly is the **mixed** variant:
use cheap, high-fan-out threads (virtual threads in Java) to absorb
blocking waits, but hand the CPU-bound segment of each task to a small,
separate, core-sized pool — isolating the resource that is actually scarce
(cores) from the resource that virtual threads make cheap (blocked-waiter
count).

## Assumptions and scope

- The blocking-wait component in every dataset is a simulated wait
  (`LockSupport.parkNanos`/`thread::sleep`), never a real socket — this
  keeps the lab's correctness fixture deterministic and host-independent;
  see "using sleep as the only I/O model" in Known traps for why this
  choice is disclosed, not hidden.
- Rust has no built-in lightweight/virtual-thread runtime; per this
  repository's policy against adding a runtime solely to chase a Java
  feature, the Rust "high-fan-out" scenario uses OS threads and is
  documented as a non-comparable counterpart, not a virtual-thread
  equivalent (rust.md, benchmark.md).
- Every variant's CPU-stage checksum is a pure function of a task's
  integer id and iteration count, asserted identical across all variants
  and both languages by a shared fixture (java.md, rust.md) before any
  timing is trusted.
- This lab does not model real socket backpressure, TLS handshake cost or
  connection-pool exhaustion — see the
  [Backpressure and Bounded Pipelines](/lab/backpressure-bounded-pipelines/)
  lab for backpressure specifically.

## Known traps

- **Using sleep as the only I/O model.** A simulated wait via
  `Thread.sleep`/`parkNanos` proves a model *can* schedule blocked tasks
  cheaply; it says nothing about real socket readiness, partial reads, or
  backpressure — this lab is explicit that its "I/O" is a stand-in, not a
  network benchmark.
- **Claiming virtual threads are low-latency threads.** Virtual threads
  reduce the *cost of blocking*, not scheduling latency or tail-latency
  variance — a carrier pool under load can still queue a runnable virtual
  thread behind others, and GC/JIT pauses affect virtual and platform
  threads identically.
- **Adding an async runtime only to win a chart.** This lab's Rust track
  deliberately uses only `std::thread` and manual deadline scheduling; a
  production system might reasonably choose `tokio`, but doing so here
  would test the runtime's scheduler, not the mechanism this lab teaches.
- **Mixing CPU capacity across models.** Comparing a 5-thread CPU pool
  against a 500-virtual-thread run on the same CPU-bound dataset and
  concluding "virtual threads are faster" ignores that both are ultimately
  bottlenecked by the same physical core count — see Result Presentation
  in benchmark.md for why this lab never publishes such a comparison.

## Pre-lab diagnostic — answer

The I/O-heavy endpoint spent most of its time *blocked* waiting on a
downstream call — exactly what virtual threads make cheap, since each
blocked task unmounts from its carrier and lets another task use it. The
image-resizing endpoint never blocks; it spends 100% of its time actively
computing on a CPU. A virtual thread doing CPU-bound work never unmounts —
it holds its carrier the entire time, so N virtual threads doing CPU work
contend for the same small number of carriers exactly as N platform
threads would contend for the same number of cores. Virtual threads did not
create additional CPU capacity for the second endpoint; there was none to
create.
