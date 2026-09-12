# Syscall amortization and scatter/gather I/O — theory

## Performance question and hypothesis

**Question:** how much does per-message kernel crossing cost, and when do
gathering writes or batching pay off?

**Hypothesis:** reducing syscall count improves throughput until
buffering delay, copy construction or socket backpressure dominates.

**What would disprove it:** if the coalesced-buffer variant (one write
call, but with an extra copy) performed identically to the scatter/gather
variant (one write call, no extra copy) — the copy this lab attributes to
coalescing would not actually cost anything — or if batching messages
into ever-larger batches kept improving throughput with no limit (nothing
would actually be trading against the reduced call count, contradicting
"until buffering delay ... dominates"), or if a correctly-implemented
partial-write loop delivered different bytes than an uninterrupted write
(partial-write handling would be broken, not merely slower).

## Learning objective

Separate three things that "fewer system calls" conflates: the fixed
per-call kernel-crossing cost itself, the cost of copying scattered data
into one contiguous buffer just so a single write call can be made
(coalescing), and the cost of *waiting* to accumulate a batch before
sending anything at all (buffering delay) — then show that scatter/gather
I/O gets the first win without the second cost, while batching trades the
first win for the third cost.

## Prerequisites

- The [Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
  lab — this lab's message header/payload split is exactly that lab's
  fixed-layout idea, applied to a network write instead of a stored
  record.
- The [UDP Ingest, Batching and Packet Loss](/lab/udp-ingest-batching/)
  lab — the same batching-vs-latency trade-off this lab's hypothesis
  states is that lab's receive-side mirror image, here on the write side.

### Pre-lab diagnostic

A benchmark reports "our gathering write reduced write() calls from 2 to
1 per message, and throughput improved 1.8x." A colleague points out that
1 fewer call per message should save much less than 1.8x if the syscall
itself is cheap. What is the *other* plausible mechanism — besides fewer
kernel crossings — that a gathering write removes, which could explain a
gain bigger than the raw call-count reduction predicts?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Kernel crossing | The cost of a system call itself — switching from user mode to kernel mode and back — independent of how much data the call moves. |
| Scatter/gather I/O | Writing (or reading) from several separate memory buffers in a single system call, without first copying them into one contiguous buffer — `writev`/`write_vectored`/`SocketChannel.write(ByteBuffer[])`. |
| Coalescing | Copying several separate pieces of data into one contiguous buffer specifically so a single, ordinary (non-vectored) write call can send them together — trades an extra copy for fewer calls. |
| Buffering delay | The time a message spends waiting to be included in a batch before the batch is actually sent — the cost side of "batching amortizes syscalls but increases queueing latency." |
| Partial write | A single write call that successfully writes fewer bytes than requested (common for large writes, or under backpressure) — not an error, but a signal the caller must retry with the remaining data. |
| Write-call proxy | This lab's `writeCalls`/`write_calls` count of application-level write invocations — an honest stand-in for syscall count, not a verified one (see the "counting application calls instead of syscalls" trap). |

## Three variants, three distinct mechanisms

1. **Single small write** — two separate `write()` calls per message
   (header, then payload). No coalescing, no batching: the honest,
   unoptimized baseline. Every message pays two kernel crossings.
2. **Coalesced buffer** — the header and payload are copied into one
   contiguous array, then sent with one `write()` call. This removes one
   kernel crossing per message, but pays for it with one extra
   memory copy per message.
3. **Scatter/gather write** — the header and payload stay in their
   original, separate buffers; a single vectored write call
   (`writev`/`write_vectored`) hands the kernel *both* buffers at once.
   This gets the same "one kernel crossing" win as coalescing, without
   the copy: the kernel gathers the bytes from wherever they already are.

The size/time-bounded batch variant goes further: it delays sending at
all until several messages have accumulated, then issues **one** vectored
write covering the whole batch — amortizing the per-call cost across many
messages, not just two buffers. This is where "syscalls/message" can drop
well below 1, at the direct cost of every message in the batch waiting
for the batch to fill (or a time bound to expire) before it is sent at
all — the buffering-delay half of this lab's hypothesis.

## Partial writes are normal, not exceptional

A `write()`/`write_vectored()` call is permitted to write fewer bytes than
requested — under backpressure (a full socket send buffer, a slow
receiver) this is common, not a rare edge case. Every sender variant in
this lab's own reference implementation loops until every byte of every
buffer is actually written, re-issuing the write call with whatever
remains. The "backpressured receiver" scenario deliberately induces this
by pairing a fast sender with an artificially slow receiver (a small
per-message delay) — under that pressure, the sender experiences real
partial writes and must handle them correctly, which this lab's
correctness suite explicitly verifies (`backpressuredReceiver*` /
`backpressured_receiver*` tests) rather than merely asserting in prose.

## Visualization 1: user/kernel transition diagram (textual fallback)

```
Single small write (2 calls/message):
  user space: build header -> [SYSCALL: write(header)] -> build payload -> [SYSCALL: write(payload)]

Coalesced buffer (1 call/message, 1 extra copy):
  user space: build header -> build payload -> COPY both into one buffer -> [SYSCALL: write(combined)]

Scatter/gather write (1 call/message, 0 extra copy):
  user space: build header -> build payload -> [SYSCALL: writev([header, payload])]
                                                  (kernel reads directly from both buffers)
```

## Visualization 2: batch composition timeline (textual fallback)

```
Size-bounded batch, batch size = N:
  message 1 arrives -> buffered, not yet sent
  message 2 arrives -> buffered, not yet sent
  ...
  message N arrives -> batch full -> ONE writev() call sends all N
                                       (message 1 waited the longest)
```

Message 1 in a full batch waits for messages 2 through N to arrive before
it is ever sent — this is the buffering delay this lab's hypothesis names
as batching's cost.

## Visualization 3: latency vs. syscalls chart (textual fallback)

```
                        Illustrative shape (see benchmark.md for real numbers)
Write calls/message:    single-write (2) > coalesced/scatter-gather (1) > batched (<1, amortized)
Per-message latency:    single-write/coalesced/scatter-gather (low, sent immediately)
                         < batched (higher — held for the batch to fill)
```

## Known traps

- **Ignoring partial writes.** Assuming a single `write()` call always
  completes the whole buffer is the single most common correctness bug
  in this lab's mechanism — every reference variant here loops correctly;
  see exercises.md for a diagnosis exercise built around this exact bug.
- **Counting application calls instead of syscalls.** This lab's
  `writeCalls`/`write_calls` accounting is an honest application-level
  proxy, never presented as a verified syscall count — see the
  Write-call proxy term above and Assumptions and scope.
- **Letting Nagle/coalescing differ silently.** TCP's own Nagle algorithm
  (or `TCP_NODELAY`) can coalesce or delay small writes at a layer below
  this lab's own code, independent of which variant is used — this lab
  does not disable Nagle by default, and any host-level socket tuning
  must be disclosed explicitly in provenance, never silently applied.
- **Measuring only throughput.** A batching variant that "wins" on
  throughput while silently making tail latency (queueing delay for the
  first message in each batch) worse is not a strict improvement — this
  lab's required metrics include p99 queue delay specifically so
  throughput alone can never be the whole story.

## Assumptions and scope

- Every benchmark and test in this lab runs over TCP loopback (127.0.0.1)
  only — no claim is made about real network behavior.
- `writeCalls`/`write_calls` is an application-level proxy for kernel
  crossings, not a verified syscall count; real syscall counts require an
  external tool (`strace -c` on Linux) applied on the native-Linux
  evidence host, not fabricated here.
- The "mixed sizes" dataset profile's full parameter sweep is exercised
  by the correctness suite; the JMH/Criterion benchmark matrix in this
  pass uses one representative fixed size for its timed region rather
  than sweeping every size in the profile as a separate benchmark
  parameter — documented here rather than silently narrowed.
- This lab does not disable or tune TCP-level Nagle/delayed-ACK behavior;
  any such tuning on the native-Linux evidence host must be disclosed
  explicitly in that run's provenance, per the "letting Nagle/coalescing
  differ silently" trap.
- Correctness (every sent message delivered and validated, explicit
  write-call and partial-write counts, correct behavior under induced
  backpressure) is verified by a fixture shared identically by both
  languages' test suites before any timing is trusted.

## Pre-lab diagnostic — answer

The other plausible mechanism is the **removed memory copy**, not just
the removed syscall. If the "2 calls" baseline is the naive
single-small-write variant and the "1 call" result is scatter/gather (not
coalescing), the comparison removed a kernel crossing *and* never paid
for a coalescing copy in the first place — but if the "1 call" result is
actually the *coalesced* variant, then the 1.8x gain includes hiding one
memory copy's cost inside what looks like a pure call-count story. Either
way, "fewer write calls" alone doesn't tell you which of (fewer kernel
crossings) or (avoided/added copies) is doing the work — exactly why this
lab measures `writeCalls`, `bytesWritten`, and separately reasons about
which variants pay a coalescing copy, rather than reporting one throughput
number and one call count as if they explained each other.
