# UDP ingest, batching and packet loss — theory

## Performance question and hypothesis

**Question:** what throughput and tail-latency trade-offs emerge when
ingesting datagrams directly into a bounded processing path?

**Hypothesis:** batching amortizes syscalls but increases queueing
latency; socket buffers, drops and CPU placement determine behavior under
burst load.

**What would disprove it:** if removing per-packet allocation (reused
buffer vs. one-packet-per-receive) made no measurable difference (the
allocation cost this lab attributes to the naive variant would not be
real), or if a genuinely unbounded queue never dropped anything under
overload (nothing would need to be bounded at all, contradicting the
whole premise), or if the zero-copy handoff cost the same as the copying
handoff regardless of payload size (removing the copy would have no
effect, meaning the copy was never the cost driver).

## Learning objective

Show that "receiving UDP fast" is not one problem but three layered
ones: the cost of the receive call itself (and whatever it allocates),
the cost of getting a received datagram from the socket into a bounded
processing path without an unbounded queue silently hiding overload, and
the *visibility* of loss — a system that drops datagrams honestly and
counts them is teaching something true; a system that drops datagrams
into an unbounded queue that just grows until the process runs out of
memory is hiding the exact failure this lab exists to make visible.

## Prerequisites

- The [Clocks and Latency Histograms](/lab/clocks-latency-histograms/) lab
  — measuring "drop rate" and "p99" honestly depends on the same
  measurement discipline (percentile reporting, avoiding coordinated
  omission) that lab establishes.
- The [SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab — the bounded
  handoff path in this lab's copying/zero-copy variants is the same
  single-producer/single-consumer discipline, just fed by UDP receives
  instead of compute-generated values.
- The [Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
  lab — this lab's datagram format is a fixed layout for the same reasons.

### Pre-lab diagnostic

A team reports "0% packet loss" for their UDP ingest service, measured by
counting how many datagrams their application successfully processed
versus how many the load generator reported sending. A colleague is
skeptical of the "0%" figure. What category of loss could this
measurement structurally never observe, no matter how carefully the
counting is done?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Kernel-level (socket) drop | The OS discards a datagram before the application ever calls receive — typically because the socket's receive buffer filled up faster than the application drained it. Visible (on Linux) via kernel counters this application-level code cannot see directly. |
| Application-level drop | The application successfully received a datagram off the socket but explicitly discarded it because its own bounded processing path (queue, slab) was full. Countable and disclosed by design in this lab. |
| Batching (receive-side) | Reading several datagrams per system call (e.g. Linux's `recvmmsg`) instead of one call per datagram — amortizes the fixed per-syscall cost across many messages. |
| Bounded lifetime | The guarantee that a zero-copy view into a reused buffer remains valid only until a specific, provable point (here: until the buffer slot is proven no longer in use before the receiver reuses it) — see the "reusing receive buffer after publication" trap. |
| Microburst | A short run of back-to-back datagrams with no inter-send delay, followed by an idle gap — as opposed to a sustained "overload" of continuous back-to-back sending. |

## Three layered costs

1. **Receive-call cost.** Every `recv`/`receive` is a system call; if the
   receive path also allocates a fresh buffer for every single datagram
   (this lab's `onePacketPerReceive`/`receive_allocating_per_packet`
   variant), that allocation is a second, avoidable cost stacked on top —
   removed by reusing one buffer across every receive
   (`reusedDirectBuffer`/`receive_with_reused_buffer`).
2. **Handoff cost.** Getting a received datagram from the receive buffer
   to wherever it's actually processed either copies the bytes
   (`copyingHandoff`) or hands over a view into a buffer the receiver
   itself still owns (`zeroCopyViewHandoff`) — the second removes a copy,
   at the cost of needing an explicit, provable rule for how long that
   view stays valid.
3. **Visibility of loss.** Regardless of how fast the receive/handoff path
   is, *something* has to happen when the processing side can't keep up
   with the arrival rate. An unbounded queue "solves" this by growing
   forever — which just delays an out-of-memory failure and hides the
   real drop rate behind ever-increasing queueing latency. This lab's
   pipelines are deliberately bounded and count every application-level
   drop explicitly (see `IngestResult`/`IngestResult` in java.md/rust.md).

## Batched receive (recvmmsg): capability-detected, not implemented here

Linux's `recvmmsg` reads multiple datagrams in a single system call —
exactly the receive-side amortization this lab's hypothesis names. This
repository's development host is macOS, which has no `recvmmsg`
equivalent, and this lab does not introduce a new native-syscall binding
(via FFM or a Rust FFI crate) solely to demonstrate one variant on one
platform — that would add a maintenance burden and platform-specific
`unsafe` surface disproportionate to a teaching lab, and design.md's own
"missing kernel drops" and capability-detection guardrails already commit
this lab to marking unsupported mechanisms as `unavailable` rather than
building a narrow, undertested implementation just to fill in a checkbox.
The "batched Linux receive where supported" variant is therefore
implemented as an explicit **capability-unavailable** state on every host
this lab currently runs on (including the native-Linux evidence host,
until a dedicated, properly-tested `recvmmsg` binding is added in a future
change) — never silently omitted, never faked with a fabricated number.

## Visualization 1: packet pipeline (textual fallback)

```
one packet per receive:    recv() -> allocate -> validate -> discard      (repeat per datagram)
reused direct buffer:      recv() -> validate -> discard                  (buffer reused, no allocation)
copying handoff:            recv() -> copy into new byte[] -> bounded queue -> consumer thread validates
zero-copy view handoff:     recv() directly into slab slot -> index onto bounded queue -> consumer reads slot in place
```

## Visualization 2: batch-size / latency curve (textual fallback)

```
                     Illustrative shape (see benchmark.md for real numbers)
Per-syscall cost:     amortized across more datagrams as batch size grows (where batching is available)
Queueing latency:     grows with batch size — a batched receive waits to accumulate a batch
                       before the application ever sees the first datagram in it
```

This lab does not have real batched-receive numbers to plot (see above);
the curve's *shape* — a trade-off, not a free win — is exactly the
"batching amortizes syscalls but increases queueing latency" half of
this lab's hypothesis, independent of whether this specific
implementation demonstrates it numerically.

## Visualization 3: drop/queue timeline (textual fallback)

```
Bounded queue depth over time, under overload:
  t0: queue fills as arrival rate > drain rate
  t1: queue reaches capacity — every arrival past this point is either
      an explicit application-level drop (bounded design) or, in a
      buggy unbounded design, an ever-growing queue that never signals
      the problem until memory runs out
```

## Known traps

- **Testing only loopback without labeling.** Every benchmark and test in
  this lab runs over 127.0.0.1 and says so explicitly — loopback has no
  real network latency, jitter, or link-layer loss, so its numbers
  characterize the receive/handoff mechanism, not real-network UDP
  behavior.
- **Ignoring kernel drops.** This lab's in-process accounting
  (`IngestResult`) can only see datagrams the application actually
  received — a datagram the kernel discarded before that never appears in
  any count here at all. See Assumptions and scope.
- **Unbounded queues hiding loss.** Every handoff pipeline in this lab
  uses a fixed-capacity queue/slab and counts every rejection explicitly
  — never an unbounded structure that defers the problem.
- **Reusing receive buffer after publication.** The zero-copy variant's
  entire design is built around never reusing a slab slot until it is
  certain the consumer is done with it — checked before receiving, not
  after (see java.md/rust.md for the exact mechanism).

## Assumptions and scope

- This lab measures exclusively over UDP loopback (127.0.0.1); it
  contains no claim about wide-area or even LAN network behavior.
- "Drop rate" in this lab's own accounting is application-level drops
  only (the bounded queue/slab was full) — kernel-level socket drops
  (visible on Linux via `/proc/net/udp`'s drops column or `ss -u -a`, not
  observable from this application-level code) are a distinct category
  this development-machine implementation cannot measure; real
  kernel-drop evidence is native-Linux-host work, not fabricated here.
- The "batched Linux receive" variant is capability-unavailable in this
  pass on every host this lab currently runs benchmarks on (see above) —
  not because the mechanism doesn't matter, but because implementing a
  correctly-tested `recvmmsg` binding is out of scope for this change.
- Correctness (every delivered datagram's payload matches its declared
  sequence, every sent datagram is accounted for as delivered, dropped, or
  corrupted) is verified by a fixture shared identically by both
  languages' test suites before any timing is trusted.

## Pre-lab diagnostic — answer

**Kernel-level (socket) drops** — a datagram the OS discards before the
application ever calls `receive()`, typically because the socket's
receive buffer filled up faster than the application could drain it. An
application-side count of "sent vs. successfully processed" can only see
datagrams that actually reached the application; it structurally cannot
distinguish "the sender's count was wrong" from "the kernel silently
discarded some datagrams before this process ever saw them." A trustworthy
loss measurement needs kernel-level counters (on Linux, values like
`/proc/net/udp`'s drops column) alongside the application's own
accounting — exactly why this lab's own `IngestResult` accounting is
explicitly scoped as "what this process observed," never presented as a
complete loss picture on its own.
